"""Single-episode coordinator. All corrections are explicit human decisions."""
from __future__ import annotations
from typing import Callable, Any
from dataclasses import asdict
from .contracts import Output
from .reset import ResetPlan, total_reset
from .seal import SealedLog, verify_chain
from .anchor import Anchor


def episode(task: dict[str, Any], *, run_id: str, weights_hash: str,
            approved_hashes: set[str], skills: list[str],
            approved_ghost_hashes: set[str],
            workers: list[Callable[[dict[str, Any]], Output]],
            visible_pair: Callable[[list[Output]], dict[str, Any]],
            ghosts: tuple[Any, Any], anchor: Anchor, sealed: SealedLog,
            reset_plan: ResetPlan,
            human_review: Callable[[dict[str, Any]], tuple[str, dict[str, Any] | None]]) -> dict[str, Any]:
    if anchor.paused:
        raise RuntimeError(f"anchor protocol paused: {anchor.pause_reason}")
    if anchor.monthly_recommitment_due():
        raise RuntimeError("monthly solo anchor re-commitment is due; episode halted")
    if weights_hash not in approved_hashes:
        raise PermissionError("unapproved model weights")
    if reset_plan.expected_weights_hash != weights_hash:
        raise PermissionError("reset plan model hash does not match episode pin")
    if len(ghosts) != 2 or {ghost.role for ghost in ghosts} != {"ghost.mandela", "ghost.matrix"}:
        raise ValueError("exactly one Mandela and one Matrix observer are required")
    for ghost in ghosts:
        if ghost.component_hash not in approved_ghost_hashes:
            raise PermissionError(f"unapproved ghost code artifact: {ghost.role}")

    anchor_slice_start = sealed.seq
    reset = total_reset(reset_plan)
    sealed_reset = {key: value for key, value in reset.items() if key != "child_env"}
    sealed.append("system", "reset_report", sealed_reset)
    if not reset["complete"]:
        raise RuntimeError("reset incomplete; episode halted")

    outputs: list[Output] = []
    for worker in workers:
        out = worker(task)
        errors = out.validate(approved_components=approved_hashes)
        if errors:
            raise ValueError(f"contract violation for {out.role}: {errors}")
        if out.component_kind != "model" or out.component_hash != weights_hash:
            raise PermissionError("worker model identity differs from pinned episode")
        if sorted(out.skill_capsules) != sorted(skills):
            raise PermissionError("worker skill capsule set differs from episode")
        sealed.append(out.role, "output", asdict(out))
        outputs.append(out)

    pair_result = visible_pair(outputs)
    sealed.append("visible_pair", "critic_result", pair_result)

    for ghost in ghosts:
        if ghost.role == "ghost.mandela":
            event = ghost.observe(run_id, outputs)
        else:
            event = ghost.observe(run_id, pair_result.get("trace", []))
        if event is not None:
            errors = event.validate(approved_code_components=approved_ghost_hashes)
            if errors:
                raise ValueError(f"ghost contract violation for {ghost.role}: {errors}")
            if event.component_hash != ghost.component_hash:
                raise PermissionError("ghost output hash differs from pinned observer artifact")
            sealed.append(ghost.role, "observation", asdict(event))

    # The anchor gets only a redacted projection of this episode's bounded sealed-log slice.
    anchor_slice_end = sealed.seq
    projection = sealed.anchor_slice(anchor_slice_start, anchor_slice_end)
    observations = [entry for entry in projection["entries"] if entry["kind"] == "observation"]
    review_items = anchor.merged_view(observations, [])
    decisions = []
    for item in review_items:
        decision, correction = human_review(item)
        result = anchor.approve(item, human_decision=decision, correction=correction)
        sealed.append(anchor.anchor_id, "human_decision", result)
        decisions.append(result)

    anchor_status = anchor.rubber_stamp_check()
    sealed.append("anchor", "rubber_stamp_status", anchor_status)
    result = {"run_id": run_id, "outputs": [asdict(output) for output in outputs],
              "visible_pair": pair_result, "human_decisions": decisions,
              "anchor_status": anchor_status, "anchor_slice": projection,
              "reset_complete": True, "chain_valid": verify_chain(sealed.path)}
    if not result["chain_valid"]:
        raise RuntimeError("sealed log verification failed")
    if anchor_status["rotate_early"]:
        result["system_paused"] = True
        result["pause_reason"] = anchor.pause_reason
    return result
