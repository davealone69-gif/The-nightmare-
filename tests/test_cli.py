"""Command-line integration test against an in-process local HTTP mock."""

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
    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        json.loads(self.rfile.read(length).decode("utf-8"))
        body = json.dumps(
            {"choices": [{"message": {"content": "CLI local mock answer"}}]}
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

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_main_runs_one_task_end_to_end(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            config_path = root / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "base_url": "http://127.0.0.1:{}/v1".format(
                            self.server.server_port
                        ),
                        "model": "mock-model",
                        "timeout_seconds": 3,
                        "data_dir": "../external-data",
                    }
                ),
                encoding="utf-8",
            )
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(["--config", str(config_path), "Answer this task"])
            self.assertEqual(status, 0, errors.getvalue())
            self.assertEqual(output.getvalue(), "CLI local mock answer\n")
            self.assertEqual(errors.getvalue(), "")

    def test_main_requires_a_model_identifier(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            config_path = root / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "base_url": "http://127.0.0.1:{}/v1".format(
                            self.server.server_port
                        ),
                        "model": "",
                        "timeout_seconds": 3,
                        "data_dir": "../external-data",
                    }
                ),
                encoding="utf-8",
            )
            output = io.StringIO()
            errors = io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                status = main(["--config", str(config_path), "Answer this task"])
            self.assertEqual(status, 2)
            self.assertEqual(output.getvalue(), "")
            self.assertIn("set model", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
