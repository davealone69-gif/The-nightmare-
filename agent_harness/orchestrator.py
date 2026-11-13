"""Single-worker orchestration loop for Phase 1."""

from .worker import GeneralWorker


class OrchestrationError(RuntimeError):
    """Raised when a worker cannot produce a usable Phase 1 result."""


class Orchestrator:
    """Coordinate exactly one worker for one task."""

    def __init__(self, worker: GeneralWorker) -> None:
        self._worker = worker

    def run_episode(self, task: str) -> str:
        if not isinstance(task, str) or not task.strip():
            raise ValueError("task must be a non-empty string")
        answer = self._worker.run(task.strip())
        if not isinstance(answer, str) or not answer.strip():
            raise OrchestrationError("worker returned no answer")
        return answer.strip()
