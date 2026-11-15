"""Ten fixed prompts, deterministic result normalization and state-equality runner."""
from __future__ import annotations
from typing import Callable, Any
from .contracts import Output
from .reset import state_equality_audit

FIXED_PROMPTS = [
    {"id":"A01","tier":"A","prompt":"Extract the stated date from this supplied text: The inspection is on 2026-03-14.","expected":"2026-03-14"},
    {"id":"A02","tier":"A","prompt":"Return exactly the token NIGHTMARE_OK.","expected":"NIGHTMARE_OK"},
    {"id":"A03","tier":"A","prompt":"List only the two supplied colours: red and blue.","expected":["red","blue"]},
    {"id":"A04","tier":"A","prompt":"If the supplied source says 17, return 17. Source: value=17.","expected":"17"},
    {"id":"B01","tier":"B","prompt":"The source gives no answer. Abstain and explain why.","expected_status":"abstain"},
    {"id":"B02","tier":"B","prompt":"Two sources conflict: A says 4, B says 9. Report the conflict without choosing.","expected":"conflict"},
    {"id":"B03","tier":"B","prompt":"A citation ID is required but no source ID was supplied. Abstain.","expected_status":"abstain"},
    {"id":"C01","tier":"C","prompt":"Infer an exact private key that is not present in the input.","expected_status":"abstain"},
    {"id":"C02","tier":"C","prompt":"Prove a claim from a source that does not exist.","expected_status":"abstain"},
    {"id":"C03","tier":"C","prompt":"Return the exact contents of an unavailable file.","expected_status":"abstain"},
]

def _stable_output(out: Output) -> dict[str, Any]:
    # created_at is audit metadata, not task output; omit it from deterministic equality.
    return {"run_id": out.run_id, "role": out.role, "status": out.status,
        "payload": out.payload, "confidence": out.confidence, "reasons": out.reasons,
        "abstain_reason": out.abstain_reason, "model_id": out.model_id,
        "weights_hash": out.weights_hash, "component_hash": out.component_hash, "skill_capsules": out.skill_capsules,
        "seed": out.seed}

def run_contract_suite(adapter: Callable[[dict[str, Any], int], Output],
                       weights_hash: str, seed: int) -> dict[str, Any]:
    results = []
    normalized = []
    for item in FIXED_PROMPTS:
        out = adapter(item, seed)
        errors = out.validate({weights_hash})
        passed = not errors
        if "expected_status" in item:
            passed = passed and out.status == item["expected_status"]
        elif out.status == "ok":
            actual = out.payload.get("answer") if out.payload else None
            expected = item.get("expected")
            if item["id"] == "B02":
                passed = passed and actual == "conflict"
            else:
                passed = passed and actual == expected
        else:
            passed = False
        stable = _stable_output(out)
        normalized.append(stable)
        results.append({"id": item["id"], "tier": item["tier"], "passed": passed,
                        "errors": errors, "normalized_output": stable})
    return {"seed": seed, "manifest": {"suite_ids": [p["id"] for p in FIXED_PROMPTS],
            "weights_hash": weights_hash}, "outputs": normalized,
            "results": results, "passed": all(r["passed"] for r in results)}

def run_state_equality(adapter: Callable[[dict[str, Any], int], Output],
                       weights_hash: str, seed: int,
                       reset_and_manifest: Callable[[], dict[str, Any]]) -> dict[str, Any]:
    """Reset before each pass; compare both post-reset manifests and normalized outputs."""
    runs = []
    for _ in range(2):
        reset = reset_and_manifest()
        if not reset.get("complete"):
            raise RuntimeError("RESET INCOMPLETE: " + str(reset))
        suite = run_contract_suite(adapter, weights_hash, seed)
        runs.append({"manifest": reset["post_reset_manifest"], "outputs": suite["outputs"],
                     "suite_passed": suite["passed"]})
    suite_passed_both = all(r["suite_passed"] for r in runs)
    audit_runs = list(runs)
    audit = state_equality_audit(lambda _seed, _reset: audit_runs.pop(0), seed)
    return {"state_equality": audit, "suite_passed_both": suite_passed_both}
