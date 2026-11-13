"""Command-line integration tests against an in-process local HTTP mock."""

from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest

from agent_harness.cli import main


class MockHandler(BaseHTTPRequestHandler):
    response_content = json.dumps(
        {"status": "COMPLETED", "confidence": 0.83, "content": "CLI local mock answer"}
    )
    payloads = []

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        type(self).payloads.append(json.loads(self.rfile.read(length).decode("utf-8")))
        body = json.dumps(
            {"choices": [{"message": {"content": type(self).response_content}}]}
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
        MockHandler.payloads = []

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def config_path(self, temp_dir: str, model: str = "mock-model") -> Path:
        root = Path(temp_dir)
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
