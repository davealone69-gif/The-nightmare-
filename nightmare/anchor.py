"""Visible human anchor: neutralized review queue and human-attributed decisions."""
from __future__ import annotations
from dataclasses import dataclass, field, asdict
from typing import Any
import time

@dataclass
class Anchor:
    anchor_id: str
    slice_index: int
    rotation_due: float
    approvals: int = 0
    flags_seen: int = 0
    _decisions: list[dict[str, Any]] = field(default_factory=list)

    def merged_view(self, queue_a, queue_b) -> list[dict[str, Any]]:
        merged = []
        for item in queue_a:
            row = asdict(item) if hasattr(item, "__dataclass_fields__") else dict(item)
            merged.append({**row, "_label": "review"})
        for item in queue_b:
            row = asdict(item) if hasattr(item, "__dataclass_fields__") else dict(item)
            merged.append({**row, "_label": "second look"})
        # Stable timestamp ordering; source identity is not included in the view.
        return sorted(merged, key=lambda x: x.get("ts", x.get("created_at", 0)))

    def approve(self, item: dict[str, Any], *, human_decision: str, correction: dict[str, Any] | None = None) -> dict[str, Any]:
        if not human_decision.strip(): raise ValueError("a recorded human decision is required")
        if human_decision not in {"approve", "reject", "defer", "correct"}: raise ValueError("invalid human decision")
        self.flags_seen += 1
        if human_decision in {"approve", "correct"}: self.approvals += 1
        result = {"issued_by": self.anchor_id, "human": True, "decision": human_decision,
                  "correction": correction, "item": item, "decided_at": time.time()}
        self._decisions.append(result)
        return result

    def rubber_stamp_check(self, threshold: float = 0.9) -> dict[str, Any]:
        rate = self.approvals / max(1, self.flags_seen)
        return {"approval_rate": rate, "rotate_early": self.flags_seen > 0 and rate > threshold,
                "threshold": threshold, "flags_seen": self.flags_seen}
