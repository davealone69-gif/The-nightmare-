"""Sequential Phase 3 orchestration for Retriever, Analyst, and Formatter."""

from dataclasses import dataclass
from enum import Enum
import re
from typing import Optional, Tuple

from .library import SourceReference
from .reset_state import (
    EpisodeStateBoundary,
    EpisodeStateSnapshot,
    ResetAuditError,
    ResetAuditReport,
)

from .worker import WorkerResult, WorkerStatus, validate_confidence


class OrchestrationError(RuntimeError):
    """Raised when a worker violates the orchestration interface."""


class ReplayAuditError(OrchestrationError):
    """Raised when same-input replay produces different declared harness state."""

    def __init__(
        self,
        first_snapshot: EpisodeStateSnapshot,
        second_snapshot: EpisodeStateSnapshot,
    ) -> None:
        self.first_snapshot = first_snapshot
        self.second_snapshot = second_snapshot
        self.worker_output_fingerprints_equal = (
            first_snapshot.worker_result_sha256
            == second_snapshot.worker_result_sha256
        )
        super().__init__(
            "replay audit failed: declared state snapshots differ "
            "(first={}, second={}); worker output fingerprints {} "
            "(informational, excluded from state equality)".format(
                first_snapshot.state_sha256,
                second_snapshot.state_sha256,
                "match" if self.worker_output_fingerprints_equal else "differ",
            )
        )


class EpisodeStatus(str, Enum):
    COMPLETED = "COMPLETED"
    ABSTAINED = "ABSTAINED"


class CitationStatus(str, Enum):
    """Whether a completed answer cites the local sources it was given."""

    NOT_REQUIRED = "NOT_REQUIRED"
    CITED = "CITED"
    UNCITED = "UNCITED"
    INVALID = "INVALID"


_CITATION_PATTERN = re.compile(r"\[([A-Za-z0-9][A-Za-z0-9_.:-]*)\]")


@dataclass(frozen=True)
class EpisodeResult:
    """Normal terminal outcome for one episode, including a valid abstention."""

    status: EpisodeStatus
    confidence: float
    worker_results: Tuple[WorkerResult, ...]
    answer: str = ""
    abstaining_worker: str = ""
    reason: str = ""

    @property
    def source_refs(self) -> Tuple[SourceReference, ...]:
        """Verified local sources retrieved for this episode, if any."""
        if self.worker_results and self.worker_results[0].role == "Retriever":
            return self.worker_results[0].source_refs
        return ()

    @property
    def citations(self) -> Tuple[str, ...]:
        """Citation IDs found in the user-visible answer, in first-use order."""
        return tuple(dict.fromkeys(_CITATION_PATTERN.findall(self.answer)))

    @property
    def citation_status(self) -> CitationStatus:
        """Flag missing or unrecognized citations; this does not verify claim truth."""
        if not self.source_refs:
            return CitationStatus.INVALID if self.citations else CitationStatus.NOT_REQUIRED
        if not self.citations:
            return CitationStatus.UNCITED
        source_ids = {source.source_id for source in self.source_refs}
        if any(citation not in source_ids for citation in self.citations):
            return CitationStatus.INVALID
        return CitationStatus.CITED

    def __post_init__(self) -> None:
        if not isinstance(self.status, EpisodeStatus):
            raise ValueError("episode status must be COMPLETED or ABSTAINED")
        object.__setattr__(self, "confidence", validate_confidence(self.confidence))
        if not self.worker_results:
            raise ValueError("episode result must include at least one worker result")
        if self.status is EpisodeStatus.COMPLETED:
            if not isinstance(self.answer, str) or not self.answer.strip():
                raise ValueError("a completed episode must include an answer")
            if self.abstaining_worker or self.reason:
                raise ValueError("a completed episode cannot have abstention details")
            if self.worker_results[-1].role != "Formatter":
                raise ValueError("a completed episode must finish with Formatter")
            if any(r.status is WorkerStatus.ABSTAINED for r in self.worker_results):
                raise ValueError("a completed episode cannot include an abstention")
            object.__setattr__(self, "answer", self.answer.strip())
        else:
            last = self.worker_results[-1]
            if last.status is not WorkerStatus.ABSTAINED:
                raise ValueError("an abstained episode must end with an abstaining worker")
            if self.abstaining_worker != last.role or self.reason != last.reason:
                raise ValueError("episode abstention details must match the worker result")
            if self.answer:
                raise ValueError("an abstained episode cannot include a final answer")


@dataclass(frozen=True)
class ReplayAuditResult:
    """Successful same-task, same-seed replay audit and second-run outcome."""

    episode_result: EpisodeResult
    first_snapshot: EpisodeStateSnapshot
    second_snapshot: EpisodeStateSnapshot

    @property
    def state_sha256(self) -> str:
        return self.first_snapshot.state_sha256

    @property
    def worker_output_fingerprints_equal(self) -> bool:
        return (
            self.first_snapshot.worker_result_sha256
            == self.second_snapshot.worker_result_sha256
        )


class Orchestrator:
    """Run each named role in order and stop safely at the first abstention."""

    EXPECTED_ROLES = ("Retriever", "Analyst", "Formatter")

    def __init__(
        self,
        retriever: object,
        analyst: object,
        formatter: object,
        *,
        state_boundary: Optional[EpisodeStateBoundary] = None,
    ) -> None:
        self._workers = (retriever, analyst, formatter)
        self._state_boundary = state_boundary or EpisodeStateBoundary()
        self._last_reset_report: Optional[ResetAuditReport] = None
        self._last_episode_snapshot: Optional[EpisodeStateSnapshot] = None
        for worker, expected_role in zip(self._workers, self.EXPECTED_ROLES):
            if getattr(worker, "role", None) != expected_role:
                raise ValueError(
                    "worker order must be Retriever, Analyst, Formatter"
                )

    @property
    def last_reset_report(self) -> Optional[ResetAuditReport]:
        """The most recent end-of-episode audit, if an episode has run."""
        return self._last_reset_report

    @property
    def last_episode_snapshot(self) -> Optional[EpisodeStateSnapshot]:
        """Snapshot of the latest run before reset; result hashes are separate."""
        return self._last_episode_snapshot

    def run_episode(self, task: str, *, seed: int = 0) -> EpisodeResult:
        if not isinstance(task, str) or not task.strip():
            raise ValueError("task must be a non-empty string")

        self._last_episode_snapshot = None
        try:
            self._state_boundary.begin(task.strip(), seed)
        except ResetAuditError as exc:
            self._last_reset_report = exc.report
            raise

        try:
            for worker in self._workers:
                self._state_boundary.record_worker_call(worker.role)
                result = worker.run(
                    task.strip(), self._state_boundary.worker_results
                )
                if not isinstance(result, WorkerResult):
                    raise OrchestrationError(
                        "{} returned an invalid result object".format(worker.role)
                    )
                if result.role != worker.role:
                    raise OrchestrationError(
                        "{} returned a result for a different role".format(worker.role)
                    )
                self._state_boundary.record_result(result)
                results = self._state_boundary.worker_results

                if result.status is WorkerStatus.ABSTAINED:
                    return EpisodeResult(
                        status=EpisodeStatus.ABSTAINED,
                        confidence=min(item.confidence for item in results),
                        worker_results=results,
                        abstaining_worker=result.role,
                        reason=result.reason,
                    )

            results = self._state_boundary.worker_results
            return EpisodeResult(
                status=EpisodeStatus.COMPLETED,
                confidence=min(item.confidence for item in results),
                worker_results=results,
                answer=results[-1].content,
            )
        finally:
            self._last_episode_snapshot = self._state_boundary.snapshot()
            self._state_boundary.reset()
            report = self._state_boundary.audit("episode_end")
            self._last_reset_report = report
            if not report.passed:
                raise ResetAuditError(report)

    def run_replay_audit(self, task: str, *, seed: int = 0) -> ReplayAuditResult:
        """Run two episodes with identical task/seed and compare declared state.

        Reset failures propagate immediately. Worker-result fingerprint changes
        are reported separately and do not, by themselves, count as a state leak.
        """
        self.run_episode(task, seed=seed)
        first_snapshot = self._last_episode_snapshot
        if first_snapshot is None:
            raise OrchestrationError("first replay produced no state snapshot")

        second_result = self.run_episode(task, seed=seed)
        second_snapshot = self._last_episode_snapshot
        if second_snapshot is None:
            raise OrchestrationError("second replay produced no state snapshot")

        if (
            not first_snapshot.same_declared_state(second_snapshot)
            or first_snapshot.state_sha256 != second_snapshot.state_sha256
        ):
            raise ReplayAuditError(first_snapshot, second_snapshot)

        return ReplayAuditResult(
            episode_result=second_result,
            first_snapshot=first_snapshot,
            second_snapshot=second_snapshot,
        )
