"""Phase 5 review queue, provenance, and capability-boundary tests."""

import fcntl
from contextlib import redirect_stdout
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import threading
import tempfile
import unittest
from unittest.mock import patch

from agent_harness.cli import main
from agent_harness.review import (
    AppendOnlyWriter,
    ReviewError,
    ReviewStore,
    STREAMS,
    build_observation_event,
    open_monitor_writer,
)


class ReviewTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.data_dir = self.root / "external-data"
        self.repo_root = Path(__file__).resolve().parents[1]

    def tearDown(self) -> None:
        self.temp.cleanup()

    def observation(self, stream: str, episode: str = "episode-1", summary: str = "Machine signal") -> dict:
        return build_observation_event(
            stream_id=stream,
            episode_id=episode,
            signal_code="CONSENSUS_DRIFT" if stream == "mandela" else "EVENT_ORDER_ANOMALY",
            severity="P2",
            confidence=0.81,
            coverage=0.94,
            summary=summary,
            evidence_refs=["event:episode-1:11"],
            component_version=stream + "-test-1",
        )

    def append_observation(self, stream: str, episode: str = "episode-1", summary: str = "Machine signal") -> dict:
        event = self.observation(stream, episode, summary)
        with open_monitor_writer(self.data_dir, stream, self.repo_root) as writer:
            writer.append(event)
        return event

    def test_monitor_stream_fds_are_write_only_and_have_no_read_api(self) -> None:
        mandela_event = self.observation("mandela")
        matrix_event = self.observation("matrix")
        with open_monitor_writer(self.data_dir, "mandela", self.repo_root) as mandela:
            with open_monitor_writer(self.data_dir, "matrix", self.repo_root) as matrix:
                self.assertIsInstance(mandela, AppendOnlyWriter)
                self.assertIsInstance(matrix, AppendOnlyWriter)
                for writer in (mandela, matrix):
                    flags = fcntl.fcntl(writer._fd, fcntl.F_GETFL)
                    self.assertEqual(flags & os.O_ACCMODE, os.O_WRONLY)
                    with self.assertRaises(OSError):
                        os.read(writer._fd, 1)
                    self.assertFalse(hasattr(writer, "read"))
                mandela.append(mandela_event)
                matrix.append(matrix_event)
        with self.assertRaises(ReviewError):
            open_monitor_writer(self.data_dir, "unknown", self.repo_root)
        with self.assertRaises(ReviewError):
            open_monitor_writer(self.repo_root / "inside-repo", "mandela", self.repo_root)
        linked_data = self.root / "linked-data"
        streams = linked_data / "phase5-review" / "streams"
        streams.parent.mkdir(parents=True)
        streams.symlink_to(self.repo_root, target_is_directory=True)
        with self.assertRaisesRegex(ReviewError, "outside its configured data directory"):
            open_monitor_writer(linked_data, "mandela", self.repo_root)
        with self.assertRaisesRegex(ReviewError, "outside its configured data directory"):
            ReviewStore(linked_data, self.repo_root)

    def test_monitor_writer_rejects_human_attribution_and_other_stream_events(self) -> None:
        machine_event = self.observation("mandela")
        forged = dict(machine_event)
        forged["source_provenance"] = dict(machine_event["source_provenance"], authorship="human")
        with open_monitor_writer(self.data_dir, "mandela", self.repo_root) as writer:
            with self.assertRaisesRegex(ReviewError, "machine observations"):
                writer.append(forged)
            with self.assertRaisesRegex(ReviewError, "stream_id"):
                writer.append(self.observation("matrix"))

    def test_human_queue_rejects_misattributed_stream_rows(self) -> None:
        event = self.observation("matrix")
        event["source_provenance"]["authorship"] = "human"
        path = self.data_dir / "phase5-review" / "streams" / "matrix.jsonl"
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(event) + "\n", encoding="utf-8")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            with self.assertRaisesRegex(ReviewError, "invalid or misattributed machine provenance"):
                store.list_queue()

    def test_append_is_append_only_and_queue_discloses_both_machine_streams(self) -> None:
        first = self.append_observation("mandela", summary="Semantic finding")
        second = self.append_observation("matrix", summary="Mechanism finding")
        third = self.append_observation("mandela", summary="Second semantic finding")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            queue = store.list_queue()
            self.assertEqual({item["observation"]["observation_id"] for item in queue}, {first["observation_id"], second["observation_id"], third["observation_id"]})
            self.assertEqual({item["observation"]["source_provenance"]["authorship"] for item in queue}, {"machine"})
            self.assertTrue(all("Machine observation from" in item["display_provenance"] for item in queue))
        for stream in ("mandela", "matrix"):
            path = self.data_dir / "phase5-review" / "streams" / (stream + ".jsonl")
            self.assertTrue(path.is_file())
            expected_count = 2 if stream == "mandela" else 1
            self.assertEqual(len(path.read_text(encoding="utf-8").splitlines()), expected_count)

    def test_queue_rejects_truncated_stream_instead_of_dropping_a_finding(self) -> None:
        self.append_observation("mandela")
        path = self.data_dir / "phase5-review" / "streams" / "mandela.jsonl"
        path.write_bytes(path.read_bytes().rstrip(b"\n"))
        with ReviewStore(self.data_dir, self.repo_root) as store:
            with self.assertRaisesRegex(ReviewError, "incomplete record"):
                store.list_queue()

    def test_oversized_existing_journal_is_rejected_before_reading(self) -> None:
        from agent_harness.review import MAX_JOURNAL_BYTES

        path = self.data_dir / "phase5-review" / "human-decisions.jsonl"
        path.parent.mkdir(parents=True)
        with path.open("wb") as journal:
            journal.truncate(MAX_JOURNAL_BYTES + 1)
        with ReviewStore(self.data_dir, self.repo_root) as store:
            with patch("agent_harness.review.os.read") as read_call:
                with self.assertRaisesRegex(ReviewError, "byte journal limit"):
                    store.list_queue()
                read_call.assert_not_called()

    def test_each_journal_stops_growing_at_the_configured_file_cap(self) -> None:
        observation = self.observation("mandela")
        with open_monitor_writer(self.data_dir, "mandela", self.repo_root) as writer:
            writer.append(observation)
        monitor_path = self.data_dir / "phase5-review" / "streams" / "mandela.jsonl"
        with patch("agent_harness.review.MAX_JOURNAL_BYTES", 1):
            with open_monitor_writer(self.data_dir, "matrix", self.repo_root) as writer:
                with self.assertRaisesRegex(ReviewError, "journal reached"):
                    writer.append(self.observation("matrix"))
            with ReviewStore(self.data_dir, self.repo_root) as store:
                with patch.object(
                    store,
                    "_read_stream",
                    side_effect=lambda stream: [observation] if stream == "mandela" else [],
                ):
                    with self.assertRaisesRegex(ReviewError, "human-decisions journal reached"):
                        store.decide(observation["observation_id"], action="approve", reviewer_id="anchor-cap")
                with patch.object(store, "_read_stream", return_value=[]):
                    with self.assertRaisesRegex(ReviewError, "next-episodes journal reached"):
                        store.prepare_next_episode("empty-episode", "next-empty-episode")
        review_root = self.data_dir / "phase5-review"
        self.assertEqual((review_root / "streams" / "matrix.jsonl").stat().st_size, 0)
        self.assertEqual((review_root / "human-decisions.jsonl").stat().st_size, 0)
        self.assertEqual((review_root / "next-episodes.jsonl").stat().st_size, 0)

    def test_approve_keeps_machine_authorship_and_only_approved_item_flows_forward(self) -> None:
        approved = self.append_observation("mandela", summary="Machine proposed finding")
        rejected = self.append_observation("matrix", summary="Machine finding to reject")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            decision = store.decide(approved["observation_id"], action="approve", reviewer_id="anchor-1")
            store.decide(rejected["observation_id"], action="reject", reviewer_id="anchor-1", reason="Evidence does not support this")
            self.assertEqual(decision["reviewer"]["kind"], "human")
            self.assertTrue(decision["monitoring_disclosed"])
            prepared = store.prepare_next_episode("episode-1", "episode-2")
            self.assertEqual(len(prepared["guidance"]), 1)
            guidance = prepared["guidance"][0]
            self.assertEqual(guidance["authorship"], "machine")
            self.assertEqual(guidance["human_action"], "approve")
            self.assertEqual(guidance["human_reviewer"], "anchor-1")
            self.assertEqual(guidance["text"], "Machine proposed finding")
            self.assertNotIn("Machine finding to reject", json.dumps(prepared))
            self.assertIn("Machine findings retain machine authorship", prepared["provenance_disclosure"])
            self.assertEqual(store.prepare_next_episode("episode-1", "episode-2"), prepared)
            with self.assertRaisesRegex(ReviewError, "already has a human decision"):
                store.decide(approved["observation_id"], action="reject", reviewer_id="anchor-1", reason="Second decision")
            with self.assertRaisesRegex(ReviewError, "different source episode"):
                store.prepare_next_episode("episode-3", "episode-2")
            with self.assertRaisesRegex(ReviewError, "must be different"):
                store.prepare_next_episode("episode-1", "episode-1")

    def test_edit_is_human_authored_and_carries_machine_source_provenance(self) -> None:
        observation = self.append_observation("matrix", summary="Machine observation")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            decision = store.decide(
                observation["observation_id"],
                action="edit",
                reviewer_id="anchor-2",
                reason="Clarified scope",
                edit_text="Human correction for the next episode",
            )
            prepared = store.prepare_next_episode("episode-1", "episode-2")
        self.assertEqual(decision["reviewer"], {"kind": "human", "id": "anchor-2"})
        self.assertEqual(decision["human_authored_text"], "Human correction for the next episode")
        guidance = prepared["guidance"][0]
        self.assertEqual(guidance["authorship"], "human")
        self.assertEqual(guidance["human_action"], "edit")
        self.assertEqual(guidance["machine_finding"], "Machine observation")
        self.assertEqual(guidance["text"], "Human correction for the next episode")

    def test_next_episode_is_blocked_until_every_finding_is_reviewed(self) -> None:
        self.append_observation("mandela")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            with self.assertRaisesRegex(ReviewError, "pending monitor findings"):
                store.prepare_next_episode("episode-1", "episode-2")

    def test_reject_and_edit_require_human_content_and_authored_id(self) -> None:
        observation = self.append_observation("mandela")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            with self.assertRaisesRegex(ReviewError, "reject requires"):
                store.decide(observation["observation_id"], action="reject", reviewer_id="anchor-1")
            with self.assertRaisesRegex(ReviewError, "edit requires"):
                store.decide(observation["observation_id"], action="edit", reviewer_id="anchor-1")
            with self.assertRaisesRegex(ReviewError, "reviewer_id"):
                store.decide(observation["observation_id"], action="approve", reviewer_id=" ")
            with self.assertRaisesRegex(ReviewError, "edit_text must be human-authored text"):
                store.decide(observation["observation_id"], action="edit", reviewer_id="anchor-1", edit_text=None)
            with self.assertRaisesRegex(ReviewError, "only valid for the edit action"):
                store.decide(observation["observation_id"], action="approve", reviewer_id="anchor-1", edit_text="unexpected")

    def test_null_human_edit_in_existing_journal_cannot_be_promoted(self) -> None:
        observation = self.append_observation("mandela")
        decision = {
            "event_schema_version": "1.0",
            "event_id": "event-null-edit",
            "decision_id": "decision-null-edit",
            "event_type": "HUMAN_REVIEW_DECISION",
            "stream_id": "human-decisions",
            "observation_id": observation["observation_id"],
            "episode_id": observation["episode_id"],
            "source_stream_id": "mandela",
            "source_component": "Mandela semantic monitor",
            "source_component_version": "mandela-test-1",
            "source_signal_code": observation["signal"]["code"],
            "action": "edit",
            "reviewer": {"kind": "human", "id": "anchor-forged"},
            "monitoring_disclosed": True,
            "recorded_at": "2026-11-14T00:00:00Z",
            "reason": "",
            "human_authored_text": None,
            "machine_finding_digest": hashlib.sha256(
                json.dumps(observation, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
            ).hexdigest(),
        }
        path = self.data_dir / "phase5-review" / "human-decisions.jsonl"
        path.write_text(json.dumps(decision) + "\n", encoding="utf-8")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            with self.assertRaisesRegex(ReviewError, "invalid or misattributed provenance"):
                store.prepare_next_episode("episode-1", "episode-2")
        self.assertEqual((self.data_dir / "phase5-review" / "next-episodes.jsonl").stat().st_size, 0)

    def test_direct_prepared_context_rejects_null_human_edit(self) -> None:
        prepared = {
            "event_schema_version": "1.0",
            "event_id": "prepared-event-1",
            "event_type": "NEXT_EPISODE_GUIDANCE_PREPARED",
            "stream_id": "next-episodes",
            "monitoring_disclosed": True,
            "provenance_disclosure": "Monitor provenance disclosed; human review recorded.",
            "source_episode_id": "episode-1",
            "next_episode_id": "episode-2",
            "source_snapshot_sha256": "a" * 64,
            "guidance_sha256": "b" * 64,
            "guidance": [
                {
                    "authorship": "human",
                    "human_action": "edit",
                    "human_reviewer": "anchor-1",
                    "machine_finding": "Machine signal",
                    "text": None,
                    "observation_id": "observation-1",
                    "decision_id": "decision-1",
                }
            ],
        }
        with self.assertRaisesRegex(ReviewError, "missing or misleading provenance"):
            ReviewStore.build_next_episode_task("A new task", prepared)

    def test_rejected_decisions_are_bound_to_observation_provenance_and_digest(self) -> None:
        observation = self.append_observation("matrix", summary="Machine finding")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            store.decide(
                observation["observation_id"],
                action="reject",
                reviewer_id="anchor-reject",
                reason="Not supported",
            )
        path = self.data_dir / "phase5-review" / "human-decisions.jsonl"
        original = json.loads(path.read_text(encoding="utf-8"))
        for field, replacement in (
            ("machine_finding_digest", "0" * 64),
            ("source_signal_code", "FABRICATED_SIGNAL"),
            ("episode_id", "another-episode"),
        ):
            with self.subTest(field=field):
                changed = dict(original)
                changed[field] = replacement
                path.write_text(json.dumps(changed) + "\n", encoding="utf-8")
                with ReviewStore(self.data_dir, self.repo_root) as store:
                    with self.assertRaisesRegex(ReviewError, "provenance|digest"):
                        store.prepare_next_episode("episode-1", "episode-2")
        path.write_text(json.dumps(original) + "\n", encoding="utf-8")

    def test_approved_decision_cannot_be_rebound_to_another_monitor_stream(self) -> None:
        observation = self.append_observation("matrix", summary="Matrix observation")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            store.decide(observation["observation_id"], action="approve", reviewer_id="anchor-approved")
        path = self.data_dir / "phase5-review" / "human-decisions.jsonl"
        original = json.loads(path.read_text(encoding="utf-8"))
        forged = dict(original)
        forged["source_stream_id"] = "mandela"
        forged["source_component"] = STREAMS["mandela"]
        path.write_text(json.dumps(forged) + "\n", encoding="utf-8")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            with self.assertRaisesRegex(ReviewError, "provenance does not match"):
                store.prepare_next_episode("episode-1", "episode-2")

    def test_cli_monitor_and_human_queue_approve_reject_edit_next(self) -> None:
        def invoke(argv: list[str]) -> int:
            with redirect_stdout(io.StringIO()):
                return main(argv)

        events = [
            self.observation("mandela", "cli-episode", "approve this"),
            self.observation("matrix", "cli-episode", "reject this"),
            self.observation("mandela", "cli-episode", "edit this"),
        ]
        # Exercise both public CLI paths, not only the storage class.
        for event in events:
            argv = [
                "--phase5", "monitor", "observe", "--data-dir", str(self.data_dir),
                "--stream", event["stream_id"], "--episode-id", "cli-episode",
                "--signal-code", event["signal"]["code"], "--severity", "P2",
                "--confidence", "0.81", "--coverage", "0.94",
                "--summary", event["signal"]["summary"],
                "--component-version", event["source_provenance"]["component_version"],
            ]
            self.assertEqual(invoke(argv), 0)
        with ReviewStore(self.data_dir, self.repo_root) as store:
            queue = store.list_queue(episode_id="cli-episode")
        by_summary = {item["observation"]["signal"]["summary"]: item["observation"]["observation_id"] for item in queue}
        self.assertEqual(set(by_summary), {"approve this", "reject this", "edit this"})
        visible = io.StringIO()
        with redirect_stdout(visible):
            self.assertEqual(main(["--phase5", "review", "queue", "--data-dir", str(self.data_dir), "--episode-id", "cli-episode"]), 0)
        human_view = json.loads(visible.getvalue())
        self.assertTrue(human_view["monitoring_disclosed"])
        self.assertEqual(set(human_view["streams"]), {"mandela", "matrix"})
        self.assertTrue(all(item["observation"]["source_provenance"]["authorship"] == "machine" for item in human_view["items"]))
        self.assertTrue(all(item["display_provenance"] for item in human_view["items"]))
        self.assertEqual(invoke(["--phase5", "review", "approve", by_summary["approve this"], "--data-dir", str(self.data_dir), "--reviewer-id", "anchor-cli"]), 0)
        self.assertEqual(invoke(["--phase5", "review", "reject", by_summary["reject this"], "--data-dir", str(self.data_dir), "--reviewer-id", "anchor-cli", "--reason", "Rejected evidence"]), 0)
        self.assertEqual(invoke(["--phase5", "review", "edit", by_summary["edit this"], "--data-dir", str(self.data_dir), "--reviewer-id", "anchor-cli", "--edit-text", "Human-authored next instruction"]), 0)
        self.assertEqual(invoke(["--phase5", "review", "next", "--data-dir", str(self.data_dir), "--source-episode", "cli-episode", "--next-episode", "cli-episode-2"]), 0)
        with ReviewStore(self.data_dir, self.repo_root) as store:
            prepared = store.prepare_next_episode("cli-episode", "cli-episode-2")
        self.assertEqual([item["human_action"] for item in prepared["guidance"]], ["approve", "edit"])

    def test_next_episode_cli_sends_only_reviewed_guidance_with_provenance(self) -> None:
        approved = self.append_observation("mandela", "source-episode", "Machine approved finding")
        rejected = self.append_observation("matrix", "source-episode", "Machine rejected finding")
        edited = self.append_observation("mandela", "source-episode", "Machine finding edited by anchor")
        dismissed = self.append_observation("matrix", "source-episode", "Machine finding to dismiss")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            store.decide(approved["observation_id"], action="approve", reviewer_id="anchor-next")
            store.decide(rejected["observation_id"], action="reject", reviewer_id="anchor-next", reason="Not supported")
            store.decide(edited["observation_id"], action="edit", reviewer_id="anchor-next", edit_text="Human-authored replacement")
            store.decide(dismissed["observation_id"], action="reject", reviewer_id="anchor-next", reason="No action needed")

        class Handler(BaseHTTPRequestHandler):
            payloads = []
            responses = [
                {"status": "COMPLETED", "confidence": 0.8, "content": "retrieved"},
                {"status": "COMPLETED", "confidence": 0.8, "content": "analysis"},
                {"status": "COMPLETED", "confidence": 0.8, "content": "formatted"},
            ]

            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                type(self).payloads.append(json.loads(self.rfile.read(length).decode("utf-8")))
                answer = type(self).responses[len(type(self).payloads) - 1]
                body = json.dumps({"choices": [{"message": {"content": json.dumps(answer)}}]}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_: object) -> None:
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            project = self.root / "project"
            project.mkdir()
            config_path = project / "config.json"
            config_path.write_text(json.dumps({
                "base_url": "http://127.0.0.1:{}/v1".format(server.server_port),
                "model": "mock-model",
                "timeout_seconds": 3,
                "data_dir": "../external-data",
            }), encoding="utf-8")
            output = io.StringIO()
            with redirect_stdout(output):
                result = main([
                    "--config", str(config_path),
                    "--episode-id", "next-episode",
                    "--next-episode-from", "source-episode",
                    "New independently reviewed task",
                ])
            self.assertEqual(result, 0)
            self.assertEqual(len(Handler.payloads), 3)
            first_user_message = Handler.payloads[0]["messages"][1]["content"]
            encoded_task = first_user_message.split("Task text (JSON string):\n", 1)[1].split("\n\nPrior worker results", 1)[0]
            actual_task = json.loads(encoded_task)
            self.assertIn("Machine findings and human-authored edits are explicitly distinguished", actual_task)
            self.assertIn("Machine approved finding", actual_task)
            self.assertIn("Human-authored replacement", actual_task)
            self.assertNotIn("Machine rejected finding", actual_task)
            self.assertNotIn("Machine finding to dismiss", actual_task)
            self.assertIn('"authorship": "machine"', actual_task)
            self.assertIn('"authorship": "human"', actual_task)
            self.assertIn("episode_id: next-episode", output.getvalue())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_prepared_next_episode_is_bound_to_review_snapshot(self) -> None:
        first = self.append_observation("mandela")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            store.decide(first["observation_id"], action="approve", reviewer_id="anchor-1")
            original = store.prepare_next_episode("episode-1", "episode-2")
        self.append_observation("matrix", summary="Late machine finding")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            with self.assertRaisesRegex(ReviewError, "pending monitor findings"):
                store.prepare_next_episode("episode-1", "episode-2")

    def test_prepared_guidance_rejects_tampered_nested_decision_provenance(self) -> None:
        observation = self.append_observation("mandela")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            store.decide(observation["observation_id"], action="approve", reviewer_id="anchor-bound")
            store.prepare_next_episode("episode-1", "episode-2")
        path = self.data_dir / "phase5-review" / "next-episodes.jsonl"
        event = json.loads(path.read_text(encoding="utf-8"))
        event["guidance"][0]["human_decision"]["reviewer"]["id"] = "forged-reviewer"
        path.write_text(json.dumps(event) + "\n", encoding="utf-8")
        with ReviewStore(self.data_dir, self.repo_root) as store:
            with self.assertRaisesRegex(ReviewError, "missing or misleading provenance"):
                store.prepare_next_episode("episode-1", "episode-2")


if __name__ == "__main__":
    unittest.main()
