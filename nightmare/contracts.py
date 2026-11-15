"""Shared, validated worker/critic/ghost output contract."""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Any, Literal
import hashlib
import json
import math
import time

SCHEMA_VERSION = "0.3"
Status = Literal["ok", "abstain", "error"]
ComponentKind = Literal["model", "code"]


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
    component_hash: str = ""
    component_kind: ComponentKind = "model"
    skill_capsules: list[str] = field(default_factory=list)
    seed: int = 0
    created_at: float = field(default_factory=time.time)

    def validate(self, approved_components: set[str] | None = None,
                 approved_code_components: set[str] | None = None) -> list[str]:
        errors: list[str] = []
        if not self.run_id.strip():
            errors.append("missing run_id")
        if not self.role.strip():
            errors.append("missing role")
        if self.status not in ("ok", "abstain", "error"):
            errors.append("invalid status")
        if not math.isfinite(self.confidence) or not 0.0 <= self.confidence <= 1.0:
            errors.append("confidence must be finite and between 0 and 1")
        if self.status == "ok" and self.payload is None:
            errors.append("ok requires payload")
        if self.status == "abstain" and not self.abstain_reason:
            errors.append("abstain requires abstain_reason")
        if self.status == "error" and not self.reasons:
            errors.append("error requires a reason")

        if self.component_kind not in ("model", "code"):
            errors.append("component_kind must be model or code")
        if not self.component_hash:
            errors.append("missing component_hash")
        elif (len(self.component_hash) != 64 or
              any(ch not in "0123456789abcdef" for ch in self.component_hash)):
            errors.append("component_hash must be a lowercase SHA-256 hex digest")

        is_ghost = self.role.startswith("ghost.")
        if is_ghost and self.component_kind != "code":
            errors.append("ghost output requires component_kind=code")
        if not is_ghost and self.component_kind != "model":
            errors.append("non-ghost output requires component_kind=model")
        if self.component_kind == "model" and approved_components is not None:
            if self.component_hash not in approved_components:
                errors.append("component_hash is not an approved model artifact")
        if self.component_kind == "code" and approved_code_components is not None:
            if self.component_hash not in approved_code_components:
                errors.append("component_hash is not an approved code artifact")

        if any(not isinstance(x, str) or not x for x in self.skill_capsules):
            errors.append("skill_capsules must contain non-empty hashes")
        if not math.isfinite(self.created_at) or self.created_at <= 0:
            errors.append("created_at must be finite and positive")
        try:
            canonical_json(asdict(self))
        except (TypeError, ValueError):
            errors.append("output is not canonical-JSON serializable")
        return errors

    def digest(self) -> str:
        return hashlib.sha256(canonical_json(asdict(self)).encode()).hexdigest()
