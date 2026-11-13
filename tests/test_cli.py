"""Command-line integration tests against an in-process local HTTP mock."""

from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

from agent_harness.cli import main


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

    def test_source_backed_query_returns_cited_excerpt_when_analyst_abstains(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = self.config_path(temp_dir)
            self.freeze_demo_library(temp_dir, config_path)
            citation = "[DOC-DEMO-FERN-GUIDE-MD]"
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
            self.assertIn("workers: Retriever -> Analyst -> Formatter", output.getvalue())
            self.assertIn("citation_status: CITED", output.getvalue())
            self.assertIn("answer_mode: EXTRACTIVE_FALLBACK", output.getvalue())
            self.assertIn("The retrieved local excerpt says:", output.getvalue())
            self.assertIn("A tree fern grows best in bright, indirect light.", output.getvalue())
            self.assertIn(citation, output.getvalue())
            self.assertIn("demo-fern-guide.md", output.getvalue())
            self.assertEqual(len(MockHandler.payloads), 1)
            self.assertIn("You are the Analyst role", MockHandler.payloads[0]["messages"][0]["content"])

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


if __name__ == "__main__":
    unittest.main()
