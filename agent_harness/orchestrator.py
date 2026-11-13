"""Sequential Phase 2 orchestration for Retriever, Analyst, and Formatter."""

from dataclasses import dataclass
from enum import Enum
from typing import Tuple

from .worker import WorkerResult, WorkerStatus, validate_confidence


class OrchestrationError(RuntimeError):
    """Raised when a worker violates the orchestration interface."""


class EpisodeStatus(str, Enum):
    COMPLETED = "COMPLETED"
    ABSTAINED = "ABSTAINED"


@dataclass(frozen=True)
class EpisodeResult:
    """Normal terminal outcome for one episode, including a valid abstention."""

    status: EpisodeStatus
    confidence: float
    worker_results: Tuple[WorkerResult, ...]
    answer: str = ""
    abstaining_worker: str = ""
    reason: str = ""

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


class Orchestrator:
    """Run each named role in order and stop safely at the first abstention."""

    EXPECTED_ROLES = ("Retriever", "Analyst", "Formatter")

    def __init__(self, retriever: object, analyst: object, formatter: object) -> None:
        self._workers = (retriever, analyst, formatter)
        for worker, expected_role in zip(self._workers, self.EXPECTED_ROLES):
            if getattr(worker, "role", None) != expected_role:
                raise ValueError(
                    "worker order must be Retriever, Analyst, Formatter"
                )

    def run_episode(self, task: str) -> EpisodeResult:
        if not isinstance(task, str) or not task.strip():
            raise ValueError("task must be a non-empty string")

        results = []
        for worker in self._workers:
            result = worker.run(task.strip(), tuple(results))
            if not isinstance(result, WorkerResult):
                raise OrchestrationError(
                    "{} returned an invalid result object".format(worker.role)
                )
            if result.role != worker.role:
                raise OrchestrationError(
                    "{} returned a result for a different role".format(worker.role)
                )
            results.append(result)

            if result.status is WorkerStatus.ABSTAINED:
                return EpisodeResult(
                    status=EpisodeStatus.ABSTAINED,
                    confidence=min(item.confidence for item in results),
                    worker_results=tuple(results),
                    abstaining_worker=result.role,
                    reason=result.reason,
                )

        return EpisodeResult(
            status=EpisodeStatus.COMPLETED,
            confidence=min(item.confidence for item in results),
            worker_results=tuple(results),
            answer=results[-1].content,
        )
