"""Single-episode coordinator. All corrections are explicit human decisions."""
from __future__ import annotations
from typing import Callable, Any
from dataclasses import asdict
from .contracts import Output
from .reset import ResetPlan, total_reset
from .seal import SealedLog, verify_chain
from .anchor import Anchor

def episode(task: dict[str, Any], *, run_id: str, weights_hash: str, approved_hashes: set[str],
            skills: list[str], workers: list[Callable[[dict[str, Any]], Output]],
            visible_pair: Callable[[list[Output]], dict[str, Any]],
            ghosts: tuple[Any, Any], anchor: Anchor, sealed: SealedLog,
            reset_plan: ResetPlan, human_review: Callable[[dict[str, Any]], tuple[str, dict[str, Any] | None]]) -> dict[str, Any]:
    if weights_hash not in approved_hashes: raise PermissionError("unapproved model weights")
    if reset_plan.expected_weights_hash != weights_hash: raise PermissionError("reset plan model hash does not match episode pin")
    reset = total_reset(reset_plan)
    sealed_reset = {k: v for k, v in reset.items() if k != "child_env"}
    sealed.append("system", "reset_report", sealed_reset)
    if not reset["complete"]: raise RuntimeError("reset incomplete; episode halted")
    outputs = []
    for worker in workers:
        out = worker(task)
        errors = out.validate(approved_hashes)
        if errors: raise ValueError(f"contract violation for {out.role}: {errors}")
        if out.component_kind != "model" or out.component_hash != weights_hash: raise PermissionError("worker model identity differs from pinned episode")
        if sorted(out.skill_capsules) != sorted(skills): raise PermissionError("worker skill capsule set differs from episode")
        sealed.append(out.role, "output", asdict(out))
        outputs.append(out)
    pair_result = visible_pair(outputs)
    sealed.append("visible_pair", "critic_result", pair_result)
    queues = []
    for ghost in ghosts:
        event = ghost.observe(run_id, outputs) if ghost.role == "ghost.mandela" else ghost.observe(run_id, pair_result.get("trace", []))
        if event is not None:
            errors = event.validate()
            if errors: raise ValueError(f"ghost contract violation for {ghost.role}: {errors}")
            sealed.append(ghost.role, "observation", asdict(event))
            queues.append({"ts": event.created_at, "signal": event.payload, "run_id": run_id})
        else: queues.append(None)
    q_a = list(ghosts[0].private_queue)
    q_b = list(ghosts[1].private_queue)
    review_items = anchor.merged_view(q_a, q_b)
    decisions = []
    for item in review_items:
        decision, correction = human_review(item)
        result = anchor.approve(item, human_decision=decision, correction=correction)
        sealed.append(anchor.anchor_id, "human_decision", result)
        decisions.append(result)
    sealed.append("anchor", "rubber_stamp_status", anchor.rubber_stamp_check())
    result = {"run_id": run_id, "outputs": [asdict(o) for o in outputs],
              "visible_pair": pair_result, "human_decisions": decisions,
              "anchor_status": anchor.rubber_stamp_check(), "reset_complete": True,
              "chain_valid": verify_chain(sealed.path)}
    if not result["chain_valid"]: raise RuntimeError("sealed log verification failed")
    return result
