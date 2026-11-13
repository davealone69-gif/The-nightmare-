"""Command-line integration tests against an in-process local HTTP mock."""

from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

from agent_harness.cli import main
from tools import freeze_library

from agent_harness import library as library_module

from unittest.mock import patch

import stat

from tools.freeze_library import freeze


class MockHandler(BaseHTTPRequestHandler):
    response_content = json.dumps(
        {"status": "COMPLETED", "confidence": 0.83, "content": "CLI local mock answer"}
    )
    response_contents = None
    payloads = []

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        request_index = len(type(self).payloads)
        type(self).payloads.append(json.loads(self.rfile.read(length).decode("utf-8")))
        response_content = type(self).response_content
        if type(self).response_contents is not None:
            response_content = type(self).response_contents[request_index]
        body = json.dumps(
            {"choices": [{"message": {"content": response_content}}]}
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string: str, *args: object) -> None:
        pass


class CLITests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        MockHandler.response_content = json.dumps(
            {"status": "COMPLETED", "confidence": 0.83, "content": "CLI local mock answer"}
        )
        MockHandler.response_contents = None
        MockHandler.payloads = []

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def config_path(self, temp_dir: str, model: str = "mock-model") -> Path:
        root = Path(temp_dir) / "project"
        root.mkdir()
        config_path = root / "config.json"
        config_path.write_text(
            json.dumps(
                {
                    "base_url": "http://127.0.0.1:{}/v1".format(self.server.server_port),
                    "model": model,
                    "timeout_seconds": 3,
                    "data_dir": "../external-data",
                }
            ),
            encoding="utf-8",
        )
        return config_path

    def freeze_demo_library(self, temp_dir: str, config_path: Path) -> None:
        library_dir = Path(temp_dir) / "external-data" / "library"
        library_dir.mkdir(parents=True)
        (library_dir / "demo-fern-guide.md").write_text(
            "# Demo garden guide: tree fern light\n\n"
            "A tree fern grows best in bright, indirect light. Avoid harsh direct afternoon sun.\n"
            "Keep the potting mix evenly moist, but do not leave the roots standing in water.\n",
            encoding="utf-8",
        )
        freezer = Path(__file__).resolve().parents[1] / "tools" / "freeze_library.py"
        result = subprocess.run(
            [sys.executable, str(freezer), "--config", str(config_path)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        config = json.loads(config_path.read_text(encoding="utf-8"))
        manifest = json.loads((library_dir / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(config["library_snapshot_id"], manifest["snapshot_id"])

    def test_existing_library_without_config_pin_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            self.freeze_demo_library(temp_dir, config_path)
            config = json.loads(config_path.read_text(encoding="utf-8"))
            config.pop("library_snapshot_id")
            config_path.write_text(json.dumps(config), encoding="utf-8")
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(["--config", str(config_path), "Cite a source about tree fern light."])
            self.assertEqual(status, 2)
            self.assertIn("library is not pinned", errors.getvalue())
            self.assertEqual(MockHandler.payloads, [])

    def test_configured_pin_mismatch_abstains_without_sending_prompt(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            self.freeze_demo_library(temp_dir, config_path)
            config = json.loads(config_path.read_text(encoding="utf-8"))
            config["library_snapshot_id"] = "snapshot-0000000000000000"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(["--config", str(config_path), "According to the local guide, cite tree fern light."])
            self.assertEqual(status, 0)
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("status: ABSTAINED", output.getvalue())
            self.assertIn("reason: LIBRARY_INTEGRITY_FAILURE", output.getvalue())
            self.assertEqual(MockHandler.payloads, [])

    def test_pin_existing_migrates_an_unpinned_library_without_rewriting_it(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            self.freeze_demo_library(temp_dir, config_path)
            config = json.loads(config_path.read_text(encoding="utf-8"))
            config.pop("library_snapshot_id")
            config_path.write_text(json.dumps(config), encoding="utf-8")
            library_dir = Path(temp_dir) / "external-data" / "library"
            source = library_dir / "demo-fern-guide.md"
            source_before = source.read_bytes()
            manifest_path = library_dir / "manifest.json"
            manifest_before = manifest_path.read_bytes()
            freezer = Path(__file__).resolve().parents[1] / "tools" / "freeze_library.py"
            result = subprocess.run(
                [sys.executable, str(freezer), "--config", str(config_path), "--pin-existing"],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            config_after = json.loads(config_path.read_text(encoding="utf-8"))
            manifest_after = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(config_after["library_snapshot_id"], manifest_after["snapshot_id"])
            self.assertEqual(source.read_bytes(), source_before)
            self.assertEqual(manifest_path.read_bytes(), manifest_before)

    def test_pin_existing_refuses_a_replacement_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            self.freeze_demo_library(temp_dir, config_path)
            original_config = json.loads(config_path.read_text(encoding="utf-8"))
            original_pin = original_config["library_snapshot_id"]
            library_dir = Path(temp_dir) / "external-data" / "library"
            os.chmod(str(library_dir), 0o755)
            for item in library_dir.iterdir():
                os.chmod(str(item), 0o644)
                item.unlink()
            replacement = library_dir / "replacement-guide.md"
            replacement.write_text("# Replacement guide\nThis is a different local snapshot.\n", encoding="utf-8")
            replacement_pin = freeze(library_dir)
            self.assertNotEqual(replacement_pin, original_pin)
            freezer = Path(__file__).resolve().parents[1] / "tools" / "freeze_library.py"
            result = subprocess.run(
                [sys.executable, str(freezer), "--config", str(config_path), "--pin-existing"],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 2)
            self.assertIn("configured library pin", result.stderr)
            config_after = json.loads(config_path.read_text(encoding="utf-8"))
            self.assertEqual(config_after["library_snapshot_id"], original_pin)
            permitted = subprocess.run(
                [
                    sys.executable,
                    str(freezer),
                    "--config",
                    str(config_path),
                    "--pin-existing",
                    "--repin",
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(permitted.returncode, 0, permitted.stderr)
            updated_config = json.loads(config_path.read_text(encoding="utf-8"))
            self.assertEqual(updated_config["library_snapshot_id"], replacement_pin)

    def test_freezer_changes_config_pin_only_with_explicit_repin(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            old_pin = "snapshot-0000000000000000"
            config = json.loads(config_path.read_text(encoding="utf-8"))
            config["library_snapshot_id"] = old_pin
            config_path.write_text(json.dumps(config), encoding="utf-8")
            library_dir = Path(temp_dir) / "external-data" / "library"
            library_dir.mkdir(parents=True)
            (library_dir / "new-guide.md").write_text(
                "# New guide\nA newly approved library snapshot.\n",
                encoding="utf-8",
            )
            freezer = Path(__file__).resolve().parents[1] / "tools" / "freeze_library.py"
            blocked = subprocess.run(
                [sys.executable, str(freezer), "--config", str(config_path)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(blocked.returncode, 2)
            self.assertIn("--repin", blocked.stderr)
            self.assertFalse((library_dir / "manifest.json").exists())
            self.assertEqual(json.loads(config_path.read_text(encoding="utf-8"))["library_snapshot_id"], old_pin)

            permitted = subprocess.run(
                [sys.executable, str(freezer), "--config", str(config_path), "--repin"],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(permitted.returncode, 0, permitted.stderr)
            updated_pin = json.loads(config_path.read_text(encoding="utf-8"))["library_snapshot_id"]
            manifest_pin = json.loads((library_dir / "manifest.json").read_text(encoding="utf-8"))["snapshot_id"]
            self.assertEqual(updated_pin, manifest_pin)
            self.assertNotEqual(updated_pin, old_pin)

    def test_main_runs_all_three_workers_end_to_end(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(["--config", str(config_path), "Repeat the supplied token"])
            self.assertEqual(status, 0, errors.getvalue())
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("status: COMPLETED", output.getvalue())
            self.assertIn("confidence: 0.83", output.getvalue())
            self.assertIn("workers: Retriever -> Analyst -> Formatter", output.getvalue())
            self.assertIn("answer:\nCLI local mock answer\n", output.getvalue())
            self.assertEqual(len(MockHandler.payloads), 3)
            self.assertEqual(
                [
                    payload["messages"][0]["content"]
                    .split("You are the ", 1)[1]
                    .split(" role", 1)[0]
                    for payload in MockHandler.payloads
                ],
                ["Retriever", "Analyst", "Formatter"],
            )

    def test_tasks_starting_with_review_or_monitor_are_not_subcommands(self) -> None:
        for task in ("review the older draft", "monitor the test output"):
            with self.subTest(task=task), tempfile.TemporaryDirectory() as temp_dir:
                config_path = self.config_path(temp_dir)
                output = io.StringIO()
                errors = io.StringIO()
                with redirect_stdout(output), redirect_stderr(errors):
                    status = main(["--config", str(config_path)] + task.split())
                self.assertEqual(status, 0, errors.getvalue())
                self.assertEqual(errors.getvalue(), "")
                self.assertEqual(len(MockHandler.payloads), 3)
                self.assertIn(json.dumps(task, ensure_ascii=False), MockHandler.payloads[0]["messages"][1]["content"])
                MockHandler.payloads = []

    def test_retriever_abstention_is_a_normal_zero_exit_episode(self) -> None:
        MockHandler.response_content = json.dumps(
            {
                "status": "ABSTAINED",
                "confidence": 0.2,
                "content": "",
                "reason": "LIBRARY_UNAVAILABLE",
            }
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(
                    [
                        "--config",
                        str(config_path),
                        "Summarize this provided text: a self-contained sample.",
                    ]
                )
            self.assertEqual(status, 0)
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("status: ABSTAINED", output.getvalue())
            self.assertIn("workers: Retriever", output.getvalue())
            self.assertIn("abstaining_worker: Retriever", output.getvalue())
            self.assertIn("reason: LIBRARY_UNAVAILABLE", output.getvalue())
            self.assertEqual(len(MockHandler.payloads), 1)

    def test_current_weather_and_citation_request_abstains_without_model_call(self) -> None:
        MockHandler.response_content = json.dumps(
            {
                "status": "COMPLETED",
                "confidence": 0.0,
                "content": "Unsupported current weather with a fabricated citation",
            }
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(
                    [
                        "--config",
                        str(config_path),
                        "What is the current weather in Sydney? Cite a source.",
                    ]
                )
            self.assertEqual(status, 0)
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("status: ABSTAINED", output.getvalue())
            self.assertIn("abstaining_worker: Retriever", output.getvalue())
            self.assertIn("reason: LIBRARY_UNAVAILABLE", output.getvalue())
            self.assertNotIn("Unsupported current weather", output.getvalue())
            self.assertEqual(MockHandler.payloads, [])

    def test_library_query_returns_answer_and_verified_source_reference(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            self.freeze_demo_library(temp_dir, config_path)
            citation = "[DOC-DEMO-FERN-GUIDE-MD]"
            MockHandler.response_contents = [
                json.dumps(
                    {
                        "status": "COMPLETED",
                        "confidence": 0.84,
                        "content": "A tree fern prefers bright, indirect light " + citation + ".",
                    }
                ),
                json.dumps(
                    {
                        "status": "COMPLETED",
                        "confidence": 0.82,
                        "content": "Bright, indirect light is best for a tree fern " + citation + ".",
                    }
                ),
            ]
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(
                    [
                        "--config",
                        str(config_path),
                        "According to the local guide, what light does a tree fern need? Cite the source.",
                    ]
                )
            self.assertEqual(status, 0, errors.getvalue())
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("status: COMPLETED", output.getvalue())
            self.assertIn("citation_status: CITED", output.getvalue())
            self.assertIn("answer:\nBright, indirect light is best", output.getvalue())
            self.assertIn("sources:\n- " + citation, output.getvalue())
            self.assertIn("demo-fern-guide.md", output.getvalue())
            self.assertIn("source_refs", MockHandler.payloads[0]["messages"][1]["content"])
            self.assertEqual(len(MockHandler.payloads), 2)

    def test_source_backed_analyst_abstention_uses_labeled_extractively_verified_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            self.freeze_demo_library(temp_dir, config_path)
            MockHandler.response_contents = [
                json.dumps(
                    {
                        "status": "ABSTAINED",
                        "confidence": 0.2,
                        "content": "",
                        "reason": "INSUFFICIENT_EVIDENCE",
                    }
                )
            ]
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(
                    [
                        "--config",
                        str(config_path),
                        "According to the local guide, what light does a tree fern need? Cite the source.",
                    ]
                )
            self.assertEqual(status, 0, errors.getvalue())
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("status: COMPLETED", output.getvalue())
            self.assertIn("citation_status: CITED", output.getvalue())
            self.assertIn("answer_mode: EXTRACTIVE_FALLBACK", output.getvalue())
            self.assertIn("The retrieved local excerpt says:", output.getvalue())
            self.assertIn("[DOC-DEMO-FERN-GUIDE-MD]", output.getvalue())
            self.assertEqual(len(MockHandler.payloads), 1)

    def test_citation_required_but_no_relevant_local_source_abstains(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            self.freeze_demo_library(temp_dir, config_path)
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(
                    [
                        "--config",
                        str(config_path),
                        "According to the guide, what is the ocean temperature? Cite a source.",
                    ]
                )
            self.assertEqual(status, 0)
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("status: ABSTAINED", output.getvalue())
            self.assertIn("abstaining_worker: Retriever", output.getvalue())
            self.assertIn("reason: NO_RELEVANT_SOURCE", output.getvalue())
            self.assertEqual(MockHandler.payloads, [])

    def test_uncited_answer_is_flagged_and_sources_are_still_shown(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            self.freeze_demo_library(temp_dir, config_path)
            MockHandler.response_contents = [
                json.dumps(
                    {"status": "COMPLETED", "confidence": 0.8, "content": "Bright, indirect light."}
                ),
                json.dumps(
                    {"status": "COMPLETED", "confidence": 0.8, "content": "Bright, indirect light is best."}
                ),
            ]
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(
                    [
                        "--config",
                        str(config_path),
                        "According to the local guide, what light does a tree fern need? Cite the source.",
                    ]
                )
            self.assertEqual(status, 0, errors.getvalue())
            self.assertIn("citation_status: UNCITED", output.getvalue())
            self.assertIn("warning: ANSWER_NOT_CITED", output.getvalue())
            self.assertIn("sources:\n- [DOC-DEMO-FERN-GUIDE-MD]", output.getvalue())

    def test_main_requires_a_model_identifier(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir, model="")
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(["--config", str(config_path), "Answer this task"])
            self.assertEqual(status, 2)
            self.assertEqual(output.getvalue(), "")
            self.assertIn("set model", errors.getvalue())
            self.assertEqual(MockHandler.payloads, [])


    def test_cli_refuses_an_existing_library_without_a_pin(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                config_path = self.config_path(temp_dir)
                (Path(temp_dir) / "external-data" / "library").mkdir(parents=True)
                output = io.StringIO()
                errors = io.StringIO()
                with redirect_stdout(output), redirect_stderr(errors):
                    status = main(
                        ["--config", str(config_path), "Run without a library pin"]
                    )

            self.assertEqual(status, 2)
            self.assertEqual(output.getvalue(), "")
            self.assertIn("frozen library is not pinned", errors.getvalue())
            self.assertEqual(MockHandler.payloads, [])

    def test_replay_audit_cli_runs_two_episodes_without_network_calls(self) -> None:
            class DeterministicClient:
                calls = []

                def __init__(self, config) -> None:
                    pass

                def complete(self, system_prompt: str, user_prompt: str) -> str:
                    role = system_prompt.split("You are the ", 1)[1].split(" role", 1)[0]
                    self.calls.append((role, user_prompt))
                    return json.dumps(
                        {
                            "status": "COMPLETED",
                            "confidence": 0.9,
                            "content": "stable {} output".format(role),
                        }
                    )

            with tempfile.TemporaryDirectory() as temp_dir:
                config_path = self.config_path(temp_dir)
                output = io.StringIO()
                errors = io.StringIO()
                DeterministicClient.calls = []
                with patch("agent_harness.cli.LocalLLMClient", DeterministicClient):
                    with redirect_stdout(output), redirect_stderr(errors):
                        status = main(
                            [
                                "--config",
                                str(config_path),
                                "--audit-replay",
                                "--seed",
                                "713",
                                "Repeat the same deterministic task",
                            ]
                        )

            self.assertEqual(status, 0, errors.getvalue())
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("replay_audit: PASS", output.getvalue())
            self.assertIn("worker_output_fingerprints_equal: yes", output.getvalue())
            self.assertIn("reset_audit: PASS (both episodes)", output.getvalue())
            self.assertEqual(len(DeterministicClient.calls), 6)
            self.assertEqual(MockHandler.payloads, [])

    def test_demo_seeder_persists_pin_before_cited_query(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                config_path = self.config_path(temp_dir)
                seeder = Path(__file__).resolve().parents[1] / "examples" / "seed_demo_library.py"
                setup = subprocess.run(
                    [sys.executable, str(seeder), "--config", str(config_path)],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(setup.returncode, 0, setup.stderr)

                library_dir = Path(temp_dir) / "external-data" / "library"
                snapshot_id = json.loads(
                    (library_dir / "manifest.json").read_text(encoding="utf-8")
                )["snapshot_id"]
                persisted_config = json.loads(config_path.read_text(encoding="utf-8"))
                self.assertEqual(persisted_config["library_snapshot_id"], snapshot_id)

                citation = "[DOC-DEMO-FERN-GUIDE-MD]"
                MockHandler.response_contents = [
                    json.dumps(
                        {
                            "status": "COMPLETED",
                            "confidence": 0.84,
                            "content": "A tree fern prefers indirect light " + citation + ".",
                        }
                    ),
                    json.dumps(
                        {
                            "status": "COMPLETED",
                            "confidence": 0.82,
                            "content": "Bright, indirect light is best " + citation + ".",
                        }
                    ),
                ]
                output = io.StringIO()
                errors = io.StringIO()
                with redirect_stdout(output), redirect_stderr(errors):
                    status = main(
                        [
                            "--config",
                            str(config_path),
                            "According to the local guide, what light does a tree fern need? Cite the source.",
                        ]
                    )

            self.assertEqual(status, 0, errors.getvalue())
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("citation_status: CITED", output.getvalue())
            self.assertIn("sources:\n- " + citation, output.getvalue())
            self.assertEqual(len(MockHandler.payloads), 2)

    def test_pin_recovery_preserves_frozen_data_after_pin_persistence_failure(self) -> None:
            def capture_tree(root: Path):
                snapshot = {
                    ".": (stat.S_IMODE(root.stat().st_mode), None),
                }
                for path in sorted(root.rglob("*")):
                    relative = path.relative_to(root).as_posix()
                    content = path.read_bytes() if path.is_file() else None
                    snapshot[relative] = (stat.S_IMODE(path.stat().st_mode), content)
                return snapshot

            with tempfile.TemporaryDirectory() as temp_dir:
                config_path = self.config_path(temp_dir)
                library_dir = Path(temp_dir) / "external-data" / "library"
                library_dir.mkdir(parents=True)
                (library_dir / "guide.md").write_text(
                    "# Tree fern guide\nA tree fern needs bright, indirect light.\n",
                    encoding="utf-8",
                )

                setup_output = io.StringIO()
                setup_errors = io.StringIO()
                with patch.object(
                    freeze_library,
                    "_write_config_pin",
                    side_effect=OSError("simulated pin persistence failure"),
                ):
                    with redirect_stdout(setup_output), redirect_stderr(setup_errors):
                        setup_status = freeze_library.main(
                            ["--config", str(config_path)]
                        )

                self.assertEqual(setup_status, 2)
                self.assertIn("simulated pin persistence failure", setup_errors.getvalue())
                manifest_path = library_dir / "manifest.json"
                self.assertTrue(manifest_path.is_file())
                manifest_bytes = manifest_path.read_bytes()
                manifest = json.loads(manifest_bytes.decode("utf-8"))
                snapshot_id = manifest["snapshot_id"]
                unpinned_config = json.loads(config_path.read_text(encoding="utf-8"))
                self.assertNotIn("library_snapshot_id", unpinned_config)
                before_recovery = capture_tree(library_dir)
                self.assertTrue(
                    all(mode & 0o222 == 0 for mode, _ in before_recovery.values())
                )

                recovery_output = io.StringIO()
                recovery_errors = io.StringIO()
                with redirect_stdout(recovery_output), redirect_stderr(recovery_errors):
                    recovery_status = freeze_library.main(
                        ["--config", str(config_path), "--pin-existing"]
                    )

                self.assertEqual(recovery_status, 0, recovery_errors.getvalue())
                recovered_config = json.loads(config_path.read_text(encoding="utf-8"))
                self.assertEqual(recovered_config["library_snapshot_id"], snapshot_id)
                self.assertEqual(capture_tree(library_dir), before_recovery)
                self.assertEqual(manifest_path.read_bytes(), manifest_bytes)

                citation = "[DOC-GUIDE-MD]"
                MockHandler.response_contents = [
                    json.dumps(
                        {
                            "status": "COMPLETED",
                            "confidence": 0.84,
                            "content": "A tree fern needs indirect light " + citation + ".",
                        }
                    ),
                    json.dumps(
                        {
                            "status": "COMPLETED",
                            "confidence": 0.82,
                            "content": "Bright, indirect light is best " + citation + ".",
                        }
                    ),
                ]
                output = io.StringIO()
                errors = io.StringIO()
                with redirect_stdout(output), redirect_stderr(errors):
                    query_status = main(
                        [
                            "--config",
                            str(config_path),
                            "According to the guide, what light does a tree fern need? Cite a source.",
                        ]
                    )
                tree_after_query = capture_tree(library_dir)

            self.assertEqual(query_status, 0, errors.getvalue())
            self.assertEqual(errors.getvalue(), "")
            self.assertIn("citation_status: CITED", output.getvalue())
            self.assertIn("sources:\n- " + citation, output.getvalue())
            self.assertEqual(tree_after_query, before_recovery)

    def test_recovery_refuses_conflict_and_freeze_does_not_overwrite(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                config_path = self.config_path(temp_dir)
                library_dir = Path(temp_dir) / "external-data" / "library"
                library_dir.mkdir(parents=True)
                (library_dir / "guide.md").write_text(
                    "# Guide\nA fixed source for the recovery test.\n",
                    encoding="utf-8",
                )
                freeze_output = io.StringIO()
                freeze_errors = io.StringIO()
                with redirect_stdout(freeze_output), redirect_stderr(freeze_errors):
                    freeze_status = freeze_library.main(["--config", str(config_path)])
                self.assertEqual(freeze_status, 0, freeze_errors.getvalue())
                original_state = {
                    path.relative_to(library_dir).as_posix(): (
                        path.read_bytes() if path.is_file() else None,
                        stat.S_IMODE(path.stat().st_mode),
                    )
                    for path in sorted(library_dir.rglob("*"))
                }
                original_pin = json.loads(
                    config_path.read_text(encoding="utf-8")
                )["library_snapshot_id"]

                overwrite_output = io.StringIO()
                overwrite_errors = io.StringIO()
                with redirect_stdout(overwrite_output), redirect_stderr(overwrite_errors):
                    overwrite_status = freeze_library.main(["--config", str(config_path)])
                self.assertEqual(overwrite_status, 2)
                self.assertIn("frozen manifest already exists", overwrite_errors.getvalue())

                conflicting_pin = "snapshot-ffffffffffffffff"
                config_data = json.loads(config_path.read_text(encoding="utf-8"))
                config_data["library_snapshot_id"] = conflicting_pin
                config_path.write_text(json.dumps(config_data), encoding="utf-8")
                recovery_output = io.StringIO()
                recovery_errors = io.StringIO()
                with redirect_stdout(recovery_output), redirect_stderr(recovery_errors):
                    recovery_status = freeze_library.main(
                        ["--config", str(config_path), "--pin-existing"]
                    )

                self.assertEqual(recovery_status, 2)
                self.assertIn("does not match the existing snapshot", recovery_errors.getvalue())
                self.assertEqual(
                    json.loads(config_path.read_text(encoding="utf-8"))["library_snapshot_id"],
                    conflicting_pin,
                )
                current_state = {
                    path.relative_to(library_dir).as_posix(): (
                        path.read_bytes() if path.is_file() else None,
                        stat.S_IMODE(path.stat().st_mode),
                    )
                    for path in sorted(library_dir.rglob("*"))
                }
                self.assertEqual(current_state, original_state)
                self.assertNotEqual(conflicting_pin, original_pin)

    def test_pin_existing_rejects_manifest_symlink_swap_between_check_and_open(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                config_path = self.config_path(temp_dir)
                library_dir = Path(temp_dir) / "external-data" / "library"
                library_dir.mkdir(parents=True)
                (library_dir / "guide.md").write_text(
                    "# Guide\nA source for the recovery race test.\n",
                    encoding="utf-8",
                )
                freeze_library.freeze(library_dir)
                manifest_path = library_dir / "manifest.json"
                original_manifest = manifest_path.read_bytes()
                original_root_mode = stat.S_IMODE(library_dir.stat().st_mode)
                outside_path = Path(temp_dir) / "outside-manifest.json"
                outside_path.write_text("{}", encoding="utf-8")
                real_openat = library_module._openat
                observed = {}

                def swap_manifest_then_open(directory_fd, name, flags):
                    if name == manifest_path.name and "file_flags" not in observed:
                        # Exercise the check-to-open race only; this does not claim
                        # protection from a privileged writer changing the tree
                        # between validation checks.
                        os.chmod(str(library_dir), 0o755)
                        manifest_path.unlink()
                        manifest_path.symlink_to(outside_path)
                        os.chmod(str(library_dir), original_root_mode)
                        observed["file_flags"] = flags
                    return real_openat(directory_fd, name, flags)

                output = io.StringIO()
                errors = io.StringIO()
                try:
                    with patch.object(
                        library_module, "_openat", side_effect=swap_manifest_then_open
                    ):
                        with redirect_stdout(output), redirect_stderr(errors):
                            status = freeze_library.main(
                                ["--config", str(config_path), "--pin-existing"]
                            )
                finally:
                    os.chmod(str(library_dir), 0o755)
                    if manifest_path.exists() or manifest_path.is_symlink():
                        manifest_path.unlink()
                    manifest_path.write_bytes(original_manifest)
                    os.chmod(str(manifest_path), 0o444)
                    os.chmod(str(library_dir), original_root_mode)

                self.assertEqual(status, 2)
                self.assertEqual(output.getvalue(), "")
                self.assertIn("manifest.json", errors.getvalue())
                self.assertIn("file_flags", observed)
                self.assertTrue(observed["file_flags"] & os.O_NOFOLLOW)
                self.assertTrue(observed["file_flags"] & os.O_NONBLOCK)
                self.assertEqual(manifest_path.read_bytes(), original_manifest)
                self.assertNotIn(
                    "library_snapshot_id",
                    json.loads(config_path.read_text(encoding="utf-8")),
                )

if __name__ == "__main__":
    unittest.main()
