"""Tests for the Phase 4 declared in-process reset-state boundary."""

import hashlib
import unittest

from agent_harness.orchestrator import Orchestrator, ReplayAuditError
from agent_harness.reset_state import (
    EpisodeStateBoundary,
    ResetAuditError,
)
from agent_harness.worker import WorkerResult, WorkerStatus


class RecordingWorker:
    def __init__(self, role: str) -> None:
        self.role = role
        self.calls = []

    def run(self, task: str, previous_results=()) -> WorkerResult:
        self.calls.append((task, tuple(item.role for item in previous_results)))
        return WorkerResult(
            role=self.role,
            status=WorkerStatus.COMPLETED,
            confidence=0.9,
            content="{} output".format(self.role),
        )


class SeededDeterministicWorker:
    """Test double with repeatable outputs; it never contacts an LLM server."""

    def __init__(self, role: str, seed: int) -> None:
        self.role = role
        self.seed = seed

    def run(self, task: str, previous_results=()) -> WorkerResult:
        prior_roles = ",".join(item.role for item in previous_results)
        material = "{}\0{}\0{}\0{}".format(
            self.seed, task, self.role, prior_roles
        ).encode("utf-8")
        digest = hashlib.sha256(material).hexdigest()
        return WorkerResult(
            role=self.role,
            status=WorkerStatus.COMPLETED,
            confidence=0.9,
            content="{} deterministic {}".format(self.role, digest),
        )


class AlternatingAnalyst:
    """Fault injection: change the declared route on the second replay."""

    role = "Analyst"

    def __init__(self) -> None:
        self.calls = 0

    def run(self, task: str, previous_results=()) -> WorkerResult:
        self.calls += 1
        if self.calls == 1:
            return WorkerResult(
                role=self.role,
                status=WorkerStatus.ABSTAINED,
                confidence=0.4,
                reason="DELIBERATE_REPLAY_MISMATCH",
            )
        return WorkerResult(
            role=self.role,
            status=WorkerStatus.COMPLETED,
            confidence=0.9,
            content="analysis output",
        )


class RaisesOnceAnalyst:
    """Fail on the first episode, then complete normally on the next one."""

    role = "Analyst"

    def __init__(self) -> None:
        self.calls = 0

    def run(self, task: str, previous_results=()) -> WorkerResult:
        self.calls += 1
        if self.calls == 1:
            raise RuntimeError("deliberate worker failure")
        return WorkerResult(
            role=self.role,
            status=WorkerStatus.COMPLETED,
            confidence=0.8,
            content="recovered analysis",
        )


class DeliberateLeakBoundary(EpisodeStateBoundary):
    """Fault injection: simulate a broken wipe implementation."""

    def reset(self) -> None:
        # Intentionally retain all tracked values to model reset residue.
        pass


class ResetStateTests(unittest.TestCase):
    def workers(self):
        return (
            RecordingWorker("Retriever"),
            RecordingWorker("Analyst"),
            RecordingWorker("Formatter"),
        )

    def test_two_episodes_start_clean_and_end_at_the_same_empty_state(self) -> None:
        workers = self.workers()
        orchestrator = Orchestrator(*workers)

        first = orchestrator.run_episode("first episode marker")
        first_report = orchestrator.last_reset_report
        second = orchestrator.run_episode("second episode marker")
        second_report = orchestrator.last_reset_report

        self.assertEqual(first.answer, "Formatter output")
        self.assertEqual(second.answer, "Formatter output")
        self.assertEqual(
            workers[0].calls,
            [("first episode marker", ()), ("second episode marker", ())],
        )
        self.assertEqual(
            workers[1].calls,
            [
                ("first episode marker", ("Retriever",)),
                ("second episode marker", ("Retriever",)),
            ],
        )
        self.assertIsNotNone(first_report)
        self.assertIsNotNone(second_report)
        self.assertTrue(first_report.passed)
        self.assertTrue(second_report.passed)
        self.assertEqual(first_report.stage, "episode_end")
        self.assertEqual(second_report.stage, "episode_end")
        self.assertEqual(first_report.non_empty_channels, ())
        self.assertEqual(second_report.non_empty_channels, ())
        self.assertEqual(
            first_report.expected_empty_state_sha256,
            second_report.expected_empty_state_sha256,
        )

    def test_same_task_and_seed_replay_matches_declared_harness_snapshot(self) -> None:
        task = "replay the same fixed episode"
        seed = 713
        workers = tuple(
            SeededDeterministicWorker(role, seed)
            for role in Orchestrator.EXPECTED_ROLES
        )
        orchestrator = Orchestrator(*workers)

        audit = orchestrator.run_replay_audit(task, seed=seed)
        first = audit.first_snapshot
        second = audit.second_snapshot

        self.assertEqual(first.seed, seed)
        self.assertEqual(second.seed, seed)
        self.assertEqual(first.task_sha256, second.task_sha256)
        self.assertTrue(first.same_declared_state(second))
        self.assertEqual(first.state_sha256, second.state_sha256)
        self.assertTrue(audit.worker_output_fingerprints_equal)
        # The deterministic test double makes these fingerprints reproducible;
        # real model outputs are reported separately from control-state equality.
        self.assertEqual(first.worker_result_sha256, second.worker_result_sha256)
        self.assertIn(
            "worker_result_content_and_confidence",
            first.separated_nondeterminism,
        )
        self.assertIn("external_model_server_internal_state", first.separated_nondeterminism)
        self.assertIn("wall_clock_timing_and_os_scheduling", first.separated_nondeterminism)
        self.assertTrue(orchestrator.last_reset_report.passed)

    def test_replay_audit_fails_when_declared_routing_state_differs(self) -> None:
        retriever = RecordingWorker("Retriever")
        analyst = AlternatingAnalyst()
        formatter = RecordingWorker("Formatter")
        orchestrator = Orchestrator(retriever, analyst, formatter)

        with self.assertRaises(ReplayAuditError) as caught:
            orchestrator.run_replay_audit("same task", seed=91)

        error = caught.exception
        self.assertIn("declared state snapshots differ", str(error))
        self.assertNotEqual(
            error.first_snapshot.state_sha256,
            error.second_snapshot.state_sha256,
        )
        self.assertFalse(error.first_snapshot.same_declared_state(error.second_snapshot))
        self.assertFalse(error.worker_output_fingerprints_equal)
        self.assertEqual(analyst.calls, 2)
        self.assertEqual(len(formatter.calls), 1)

    def test_worker_exception_cleans_state_before_the_next_episode(self) -> None:
        retriever = RecordingWorker("Retriever")
        analyst = RaisesOnceAnalyst()
        formatter = RecordingWorker("Formatter")
        orchestrator = Orchestrator(retriever, analyst, formatter)

        with self.assertRaisesRegex(RuntimeError, "deliberate worker failure"):
            orchestrator.run_episode("first attempt")

        self.assertTrue(orchestrator.last_reset_report.passed)
        self.assertEqual(orchestrator.last_episode_snapshot.worker_roles, ("Retriever",))

        result = orchestrator.run_episode("second attempt")

        self.assertEqual(result.answer, "Formatter output")
        self.assertTrue(orchestrator.last_reset_report.passed)
        self.assertEqual(
            retriever.calls,
            [("first attempt", ()), ("second attempt", ())],
        )
        self.assertEqual(analyst.calls, 2)
        self.assertEqual(len(formatter.calls), 1)

    def test_deliberate_reset_leak_fails_closed_and_blocks_the_next_episode(self) -> None:
        workers = self.workers()
        boundary = DeliberateLeakBoundary()
        orchestrator = Orchestrator(*workers, state_boundary=boundary)

        with self.assertRaises(ResetAuditError) as caught:
            orchestrator.run_replay_audit("leak this episode", seed=7)

        self.assertEqual(caught.exception.report.stage, "episode_end")
        self.assertFalse(caught.exception.report.passed)
        self.assertEqual(
            caught.exception.report.non_empty_channels,
            ("prompt_context", "tool_results", "worker_counters"),
        )
        self.assertEqual([len(worker.calls) for worker in workers], [1, 1, 1])

        with self.assertRaises(ResetAuditError) as next_episode:
            orchestrator.run_episode("must not run")

        self.assertEqual(next_episode.exception.report.stage, "episode_start")
        self.assertEqual([len(worker.calls) for worker in workers], [1, 1, 1])

    def test_boundary_reset_clears_all_declared_mutable_channels(self) -> None:
        boundary = EpisodeStateBoundary()
        boundary.begin("task")
        boundary.record_worker_call("Retriever")
        boundary.record_result(object())
        self.assertFalse(boundary.audit("during_episode").passed)

        boundary.reset()
        report = boundary.audit("after_reset")

        self.assertTrue(report.passed)
        self.assertEqual(report.non_empty_channels, ())
        self.assertEqual(boundary.worker_results, ())


if __name__ == "__main__":
    unittest.main()
