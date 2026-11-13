"""Tests for the one-worker orchestrator."""

import unittest

from agent_harness.orchestrator import OrchestrationError, Orchestrator


class FakeWorker:
    def __init__(self, answer: str) -> None:
        self.answer = answer
        self.tasks = []

    def run(self, task: str) -> str:
        self.tasks.append(task)
        return self.answer


class OrchestratorTests(unittest.TestCase):
    def test_invokes_worker_once_and_returns_answer(self) -> None:
        worker = FakeWorker("  answer  ")
        self.assertEqual(Orchestrator(worker).run_episode("  task  "), "answer")
        self.assertEqual(worker.tasks, ["task"])

    def test_rejects_empty_task(self) -> None:
        with self.assertRaisesRegex(ValueError, "non-empty"):
            Orchestrator(FakeWorker("answer")).run_episode(" ")

    def test_fails_clearly_when_worker_has_no_answer(self) -> None:
        with self.assertRaisesRegex(OrchestrationError, "no answer"):
            Orchestrator(FakeWorker("  ")).run_episode("task")


if __name__ == "__main__":
    unittest.main()
