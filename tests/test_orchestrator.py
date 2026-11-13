"""Tests for Phase 2 role routing and normal abstention outcomes."""

import unittest

from agent_harness.orchestrator import (
    EpisodeStatus,
    OrchestrationError,
    Orchestrator,
)
from agent_harness.worker import WorkerResult, WorkerStatus


class FakeWorker:
    def __init__(self, role: str, status: WorkerStatus = WorkerStatus.COMPLETED) -> None:
        self.role = role
        self.status = status
        self.calls = []

    def run(self, task: str, previous_results=()) -> WorkerResult:
        self.calls.append((task, tuple(result.role for result in previous_results)))
        return WorkerResult(
            role=self.role,
            status=self.status,
            confidence=0.9 if self.status is WorkerStatus.COMPLETED else 0.4,
            content="{} output".format(self.role)
            if self.status is WorkerStatus.COMPLETED
            else "",
            reason="INSUFFICIENT_EVIDENCE"
            if self.status is WorkerStatus.ABSTAINED
            else "",
        )


class OrchestratorTests(unittest.TestCase):
    def workers(self, abstain_at: str = ""):
        return (
            FakeWorker("Retriever", WorkerStatus.ABSTAINED if abstain_at == "Retriever" else WorkerStatus.COMPLETED),
            FakeWorker("Analyst", WorkerStatus.ABSTAINED if abstain_at == "Analyst" else WorkerStatus.COMPLETED),
            FakeWorker("Formatter", WorkerStatus.ABSTAINED if abstain_at == "Formatter" else WorkerStatus.COMPLETED),
        )

    def test_routes_in_order_and_returns_formatter_output(self) -> None:
        retriever, analyst, formatter = self.workers()
        retriever_result = WorkerResult(
            "Retriever", WorkerStatus.COMPLETED, 0.95, "in-task context"
        )
        analyst_result = WorkerResult(
            "Analyst", WorkerStatus.COMPLETED, 0.8, "analysis"
        )
        formatter_result = WorkerResult(
            "Formatter", WorkerStatus.COMPLETED, 0.9, "final answer"
        )
        retriever.run = lambda task, previous=(): retriever_result
        analyst.run = lambda task, previous=(): analyst_result
        formatter.run = lambda task, previous=(): formatter_result

        outcome = Orchestrator(retriever, analyst, formatter).run_episode(" task ")

        self.assertIs(outcome.status, EpisodeStatus.COMPLETED)
        self.assertEqual(outcome.answer, "final answer")
        self.assertEqual(outcome.confidence, 0.8)
        self.assertEqual(
            [result.role for result in outcome.worker_results],
            ["Retriever", "Analyst", "Formatter"],
        )

    def test_each_role_can_abstain_as_a_normal_episode_outcome(self) -> None:
        for role in ("Retriever", "Analyst", "Formatter"):
            with self.subTest(role=role):
                workers = self.workers(abstain_at=role)
                outcome = Orchestrator(*workers).run_episode("task")
                self.assertIs(outcome.status, EpisodeStatus.ABSTAINED)
                self.assertEqual(outcome.abstaining_worker, role)
                self.assertEqual(outcome.reason, "INSUFFICIENT_EVIDENCE")
                self.assertEqual(outcome.answer, "")
                self.assertEqual(
                    [result.role for result in outcome.worker_results],
                    list(Orchestrator.EXPECTED_ROLES[: len(outcome.worker_results)]),
                )
                self.assertEqual(
                    [len(worker.calls) for worker in workers],
                    [1 if i <= Orchestrator.EXPECTED_ROLES.index(role) else 0 for i in range(3)],
                )

    def test_prior_results_are_routed_to_later_workers(self) -> None:
        workers = self.workers()
        outcome = Orchestrator(*workers).run_episode("task")
        self.assertIs(outcome.status, EpisodeStatus.COMPLETED)
        self.assertEqual(workers[0].calls, [("task", ())])
        self.assertEqual(workers[1].calls, [("task", ("Retriever",))])
        self.assertEqual(workers[2].calls, [("task", ("Retriever", "Analyst"))])

    def test_rejects_empty_task_and_wrong_role_order(self) -> None:
        with self.assertRaisesRegex(ValueError, "non-empty"):
            Orchestrator(*self.workers()).run_episode(" ")
        with self.assertRaisesRegex(ValueError, "Retriever, Analyst, Formatter"):
            Orchestrator(FakeWorker("Analyst"), FakeWorker("Retriever"), FakeWorker("Formatter"))

    def test_rejects_worker_interface_violation(self) -> None:
        retriever, analyst, formatter = self.workers()
        retriever.run = lambda task, previous=(): "not a WorkerResult"
        with self.assertRaisesRegex(OrchestrationError, "invalid result"):
            Orchestrator(retriever, analyst, formatter).run_episode("task")


if __name__ == "__main__":
    unittest.main()
