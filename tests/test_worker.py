"""Tests for Phase 2 worker contracts and response validation."""

import json
import math
import unittest

from agent_harness.worker import (
    Analyst,
    Formatter,
    Retriever,
    WorkerResult,
    WorkerStatus,
    parse_worker_response,
)


class FakeClient:
    def __init__(self, response: str) -> None:
        self.response = response
        self.calls = []

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        self.calls.append((system_prompt, user_prompt))
        return self.response


def response(status: str = "COMPLETED", confidence: object = 0.8, **fields: object) -> str:
    payload = {
        "status": status,
        "confidence": confidence,
        "content": "worker output" if status == "COMPLETED" else "",
    }
    payload.update(fields)
    return json.dumps(payload)


class WorkerTests(unittest.TestCase):
    def test_three_named_roles_share_a_validated_contract(self) -> None:
        for role_type, expected_role in (
            (Retriever, "Retriever"),
            (Analyst, "Analyst"),
            (Formatter, "Formatter"),
        ):
            with self.subTest(role=expected_role):
                client = FakeClient(response())
                worker = role_type(client)
                result = worker.run("  task  ")
                self.assertEqual(worker.role, expected_role)
                self.assertEqual(result.role, expected_role)
                self.assertIs(result.status, WorkerStatus.COMPLETED)
                self.assertEqual(result.content, "worker output")
                self.assertEqual(worker.tool_names, ())
                self.assertEqual(len(client.calls), 1)
                self.assertIn('"task"', client.calls[0][1])

    def test_retriever_prompt_forbids_invented_sources_and_discloses_no_library(self) -> None:
        prompt = Retriever(FakeClient(response())).system_prompt.lower()
        self.assertIn("no search tool", prompt)
        self.assertIn("approved evidence library", prompt)
        self.assertIn("never invent sources", prompt)
        self.assertIn("library_unavailable", prompt)

    def test_retriever_deterministically_abstains_on_live_weather_source_request(self) -> None:
        client = FakeClient(
            response(
                "COMPLETED",
                0.0,
                content="Unsupported current weather with an invented source",
            )
        )
        result = Retriever(client).run(
            "What is the current weather in Sydney? Cite a source."
        )
        self.assertIs(result.status, WorkerStatus.ABSTAINED)
        self.assertEqual(result.reason, "LIBRARY_UNAVAILABLE")
        self.assertEqual(result.content, "")
        self.assertEqual(client.calls, [])

    def test_explicit_abstention_parses_as_a_valid_result(self) -> None:
        result = parse_worker_response(
            "Retriever",
            response(
                "ABSTAINED",
                0.2,
                reason="LIBRARY_UNAVAILABLE",
            ),
        )
        self.assertIs(result.status, WorkerStatus.ABSTAINED)
        self.assertEqual(result.reason, "LIBRARY_UNAVAILABLE")
        self.assertEqual(result.confidence, 0.2)

    def test_invalid_json_and_invalid_confidence_fail_closed_as_abstentions(self) -> None:
        for text in (
            "not JSON",
            response(confidence=True),
            response(confidence=1.1),
            response(confidence=-0.1),
            response(confidence=float("nan")),
        ):
            with self.subTest(response=text):
                result = parse_worker_response("Analyst", text)
                self.assertIs(result.status, WorkerStatus.ABSTAINED)
                self.assertEqual(result.reason, "INVALID_RESPONSE")
                self.assertEqual(result.confidence, 0.0)

    def test_zero_confidence_completion_becomes_low_confidence_abstention(self) -> None:
        result = parse_worker_response(
            "Analyst",
            response("COMPLETED", 0.0, content="Unsupported factual claim"),
        )
        self.assertIs(result.status, WorkerStatus.ABSTAINED)
        self.assertEqual(result.reason, "LOW_CONFIDENCE")
        self.assertEqual(result.confidence, 0.0)

    def test_accepts_json_fenced_response(self) -> None:
        result = parse_worker_response(
            "Formatter",
            '```json\n{"status":"COMPLETED","confidence":0.75,'
            '"content":"formatted"}\n```',
        )
        self.assertIs(result.status, WorkerStatus.COMPLETED)
        self.assertEqual(result.content, "formatted")

    def test_invalid_confidence_is_rejected_by_result_contract(self) -> None:
        for confidence in (True, float("nan"), math.inf, -0.01, 0.0, 1.01):
            with self.subTest(confidence=confidence):
                with self.assertRaisesRegex(ValueError, "confidence"):
                    WorkerResult(
                        role="Analyst",
                        status=WorkerStatus.COMPLETED,
                        confidence=confidence,
                        content="answer",
                    )

    def test_worker_rejects_empty_task(self) -> None:
        with self.assertRaisesRegex(ValueError, "non-empty"):
            Retriever(FakeClient(response())).run("  ")


if __name__ == "__main__":
    unittest.main()
