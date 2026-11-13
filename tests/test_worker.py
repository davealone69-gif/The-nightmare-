"""Tests for the Phase 1 single worker."""

import unittest

from agent_harness.worker import GeneralWorker


class FakeClient:
    def __init__(self) -> None:
        self.calls = []

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        self.calls.append((system_prompt, user_prompt))
        return "worker answer"


class WorkerTests(unittest.TestCase):
    def test_runs_task_with_fixed_prompt_and_no_tools(self) -> None:
        client = FakeClient()
        worker = GeneralWorker(client)
        self.assertEqual(worker.run("  explain this  "), "worker answer")
        self.assertEqual(worker.tool_names, ())
        self.assertEqual(client.calls, [(worker.system_prompt, "explain this")])

    def test_rejects_empty_task(self) -> None:
        with self.assertRaisesRegex(ValueError, "non-empty"):
            GeneralWorker(FakeClient()).run("  ")


if __name__ == "__main__":
    unittest.main()
