"""Shared, validated worker/critic/ghost output contract."""
from __future__ import annotations
from dataclasses import dataclass, field, asdict
from typing import Any, Literal
import hashlib, json, math, time

SCHEMA_VERSION = "1.0"
Status = Literal["ok", "abstain", "error"]

def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)

@dataclass
class Output:
    run_id: str
    role: str
    status: Status
    payload: dict[str, Any] | None = None
    confidence: float = 0.0
    reasons: list[str] = field(default_factory=list)
    abstain_reason: str | None = None
    model_id: str = ""
    weights_hash: str = ""
    skill_capsules: list[str] = field(default_factory=list)
    seed: int = 0
    created_at: float = field(default_factory=time.time)

    def validate(self, approved_weights: set[str] | None = None) -> list[str]:
        errors: list[str] = []
        if not self.run_id.strip(): errors.append("missing run_id")
        if not self.role.strip(): errors.append("missing role")
        if self.status not in ("ok", "abstain", "error"): errors.append("invalid status")
        if not math.isfinite(self.confidence) or not 0.0 <= self.confidence <= 1.0:
            errors.append("confidence must be finite and between 0 and 1")
        if self.status == "ok" and self.payload is None: errors.append("ok requires payload")
        if self.status == "abstain" and not self.abstain_reason: errors.append("abstain requires abstain_reason")
        if self.status == "error" and not self.reasons: errors.append("error requires a reason")
        if not self.weights_hash: errors.append("missing weights_hash")
        if approved_weights is not None and self.weights_hash not in approved_weights:
            errors.append("weights_hash is not approved")
        if any(not isinstance(x, str) or not x for x in self.skill_capsules):
            errors.append("skill_capsules must contain non-empty hashes")
        if self.created_at <= 0: errors.append("created_at must be positive")
        try: canonical_json(asdict(self))
        except (TypeError, ValueError): errors.append("output is not canonical-JSON serializable")
        return errors

    def digest(self) -> str:
        return hashlib.sha256(canonical_json(asdict(self)).encode()).hexdigest()
