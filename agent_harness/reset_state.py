"""Declared in-process episode state and reset-state equality audit.

The audit covers only mutable state owned by this harness: the active prompt
context, results collected during one episode, and per-role call counters. It
does not inspect or control model-server internals or operating-system state.
"""

from dataclasses import dataclass
import hashlib
import json
from typing import Dict, List, Optional, Tuple


_EMPTY_STATE = {
    "prompt_context": None,
    "tool_results": [],
    "worker_counters": {},
}
_EXPECTED_EMPTY_STATE_SHA256 = hashlib.sha256(
    json.dumps(_EMPTY_STATE, sort_keys=True, separators=(",", ":")).encode("utf-8")
).hexdigest()


@dataclass(frozen=True)
class ResetAuditReport:
    """Non-sensitive audit result for the declared empty-state boundary."""

    stage: str
    passed: bool
    non_empty_channels: Tuple[str, ...]
    expected_empty_state_sha256: str = _EXPECTED_EMPTY_STATE_SHA256


@dataclass(frozen=True)
class EpisodeStateSnapshot:
    """Replay snapshot of deterministic harness state and separate outputs.

    Worker-result fingerprints are retained separately because real model
    responses may vary even when the harness inputs and routing are identical.
    """

    task_sha256: str
    seed: int
    worker_roles: Tuple[str, ...]
    worker_counters: Tuple[Tuple[str, int], ...]
    worker_result_sha256: Tuple[str, ...]
    separated_nondeterminism: Tuple[str, ...] = (
        "worker_result_content_and_confidence",
        "external_model_server_internal_state",
        "wall_clock_timing_and_os_scheduling",
    )

    @property
    def state_sha256(self) -> str:
        """Digest only the declared deterministic inputs and routing state."""
        payload = {
            "task_sha256": self.task_sha256,
            "seed": self.seed,
            "worker_roles": self.worker_roles,
            "worker_counters": self.worker_counters,
        }
        encoded = json.dumps(
            payload, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    def same_declared_state(self, other: object) -> bool:
        """Compare the task, seed, worker routing, and per-role call counts."""
        return isinstance(other, EpisodeStateSnapshot) and (
            self.task_sha256,
            self.seed,
            self.worker_roles,
            self.worker_counters,
        ) == (
            other.task_sha256,
            other.seed,
            other.worker_roles,
            other.worker_counters,
        )


class ResetAuditError(RuntimeError):
    """Raised when an episode starts or ends with undeclared residual state."""

    def __init__(self, report: ResetAuditReport) -> None:
        self.report = report
        channels = ", ".join(report.non_empty_channels) or "unknown"
        super().__init__(
            "reset-state audit failed at {}: residual state in {}".format(
                report.stage, channels
            )
        )


class EpisodeStateBoundary:
    """Track and clear the mutable state owned by one harness episode."""

    def __init__(self) -> None:
        self._prompt_context: Optional[str] = None
        self._seed: Optional[int] = None
        self._tool_results: List[object] = []
        self._worker_counters: Dict[str, int] = {}

    @property
    def worker_results(self) -> Tuple[object, ...]:
        """A read-only view of results from the currently active episode."""
        return tuple(self._tool_results)

    def begin(self, task: str, seed: int = 0) -> None:
        """Start an episode only when the prior state boundary is clean."""
        self.require_empty("episode_start")
        if isinstance(seed, bool) or not isinstance(seed, int):
            raise ValueError("episode seed must be an integer")
        self._prompt_context = task
        self._seed = seed

    def record_worker_call(self, role: str) -> None:
        """Record an execution counter inside the current episode boundary."""
        self._worker_counters[role] = self._worker_counters.get(role, 0) + 1

    def record_result(self, result: object) -> None:
        """Retain a result only until this episode's state boundary is closed."""
        self._tool_results.append(result)

    def reset(self) -> None:
        """Wipe all tracked mutable episode state."""
        self._prompt_context = None
        self._seed = None
        self._tool_results.clear()
        self._worker_counters.clear()

    @staticmethod
    def _result_digest(result: object) -> str:
        """Fingerprint one typed result without exposing its contents."""
        source_refs = getattr(result, "source_refs", ())
        payload = {
            "role": getattr(result, "role", ""),
            "status": getattr(getattr(result, "status", None), "value", ""),
            "confidence": getattr(result, "confidence", None),
            "content": getattr(result, "content", ""),
            "reason": getattr(result, "reason", ""),
            "source_refs": [
                {
                    "source_id": getattr(source, "source_id", ""),
                    "title": getattr(source, "title", ""),
                    "path": getattr(source, "path", ""),
                    "sha256": getattr(source, "sha256", ""),
                }
                for source in source_refs
            ],
        }
        encoded = json.dumps(
            payload, sort_keys=True, separators=(",", ":"), default=str
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    def snapshot(self) -> EpisodeStateSnapshot:
        """Capture this run's declared state before its boundary is wiped."""
        if self._prompt_context is None or self._seed is None:
            raise ValueError("cannot snapshot an inactive episode")
        return EpisodeStateSnapshot(
            task_sha256=hashlib.sha256(
                self._prompt_context.encode("utf-8")
            ).hexdigest(),
            seed=self._seed,
            worker_roles=tuple(
                str(getattr(result, "role", "")) for result in self._tool_results
            ),
            worker_counters=tuple(sorted(self._worker_counters.items())),
            worker_result_sha256=tuple(
                self._result_digest(result) for result in self._tool_results
            ),
        )

    def audit(self, stage: str) -> ResetAuditReport:
        """Compare declared channels with their fixed empty-state baseline."""
        non_empty = []
        if self._prompt_context is not None or self._seed is not None:
            non_empty.append("prompt_context")
        if self._tool_results:
            non_empty.append("tool_results")
        if self._worker_counters:
            non_empty.append("worker_counters")
        channels = tuple(non_empty)
        return ResetAuditReport(
            stage=stage,
            passed=not channels,
            non_empty_channels=channels,
        )

    def require_empty(self, stage: str) -> ResetAuditReport:
        """Return a passing report or stop execution on detected residue."""
        report = self.audit(stage)
        if not report.passed:
            raise ResetAuditError(report)
        return report
