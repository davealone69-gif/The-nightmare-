"""Visible human anchor: blinded queue, bounded solo re-commitment and fail-closed rotation."""
from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any
import secrets
import time

IDENTITY_FIELDS = {
    "role", "source_id", "observer_id", "ghost_id", "queue_id",
    "component_hash", "component_kind", "model_id", "actor",
}
ROTATION_EVENTS = {
    "rubber_stamp_threshold", "failed_spot_audit", "queue_contradiction",
    "sealed_log_integrity_failure", "unauthorized_log_access",
    "anchor_reports_unsafe", "missed_monthly_recommitment",
}


@dataclass
class Anchor:
    anchor_id: str
    slice_index: int
    rotation_due: float
    approvals: int = 0
    flags_seen: int = 0
    _decisions: list[dict[str, Any]] = field(default_factory=list)
    last_recommitted_at: float = 0.0
    paused: bool = False
    pause_reason: str | None = None

    def merged_view(self, queue_a, queue_b) -> list[dict[str, Any]]:
        """Merge both private queues without preserving a source-to-label mapping."""
        merged = []
        for item in (*tuple(queue_a), *tuple(queue_b)):
            row = dict(item) if isinstance(item, dict) else {
                key: value for key, value in vars(item).items()
            }
            for key in IDENTITY_FIELDS:
                row.pop(key, None)
            # Never let nested metadata reintroduce observer identity.
            payload = row.get("payload")
            if isinstance(payload, dict):
                for key in IDENTITY_FIELDS:
                    payload.pop(key, None)
            row["_label"] = secrets.choice(("review", "second look"))
            merged.append(row)
        return sorted(merged, key=lambda x: x.get("ts", x.get("created_at", 0)))

    def approve(self, item: dict[str, Any], *, human_decision: str,
                correction: dict[str, Any] | None = None) -> dict[str, Any]:
        if self.paused:
            raise RuntimeError("anchor protocol is paused; no decisions may be issued")
        if not isinstance(human_decision, str) or not human_decision.strip():
            raise ValueError("a recorded human decision is required")
        if human_decision not in {"approve", "reject", "defer", "correct"}:
            raise ValueError("invalid human decision")
        self.flags_seen += 1
        if human_decision in {"approve", "correct"}:
            self.approvals += 1
        result = {"issued_by": self.anchor_id, "human": True,
                  "decision": human_decision, "correction": correction,
                  "item": item, "decided_at": time.time()}
        self._decisions.append(result)
        return result

    def rubber_stamp_check(self, threshold: float = 0.9, window: int = 20) -> dict[str, Any]:
        if not 0.0 < threshold <= 1.0 or window < 1:
            raise ValueError("invalid rubber-stamp threshold or window")
        recent = self._decisions[-window:]
        approvals = sum(d["decision"] in {"approve", "correct"} for d in recent)
        rate = approvals / len(recent) if recent else 0.0
        triggered = len(recent) == window and rate > threshold
        return {"approval_rate": rate, "rotate_early": triggered,
                "threshold": threshold, "window_decisions": window,
                "decisions_observed": len(recent), "approvals": approvals}

    def solo_recommit(self, *, slice_reopened: bool, slice_reread: bool,
                      stop_button_tested: bool, human_confirmation: bool,
                      now: float | None = None) -> dict[str, Any]:
        """Monthly solo-anchor check; failure pauses the system."""
        timestamp = time.time() if now is None else now
        if not all((slice_reopened, slice_reread, stop_button_tested, human_confirmation)):
            self.pause("monthly solo re-commitment incomplete")
            raise PermissionError("re-commitment requires reopened/reread slice, tested stop button and human affirmation")
        record = {"issued_by": self.anchor_id, "kind": "solo_monthly_recommitment",
                  "slice_reopened": True, "slice_reread": True,
                  "stop_button_tested": True, "human_confirmation": True,
                  "decided_at": timestamp}
        self._decisions.append(record)
        self.last_recommitted_at = timestamp
        self.paused = False
        self.pause_reason = None
        return record

    def event_trigger(self, reason: str) -> dict[str, Any]:
        if reason not in ROTATION_EVENTS:
            raise ValueError("event trigger is not in the approved rotation trigger set")
        self.pause(reason)
        return {"event": "solo_anchor_rotation_required", "reason": reason,
                "system_paused": True, "replacement_anchor_available": False,
                "resume_requires": "independent human review and explicit re-authorization"}

    def pause(self, reason: str) -> None:
        self.paused = True
        self.pause_reason = reason
