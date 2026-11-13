"""Tests for the local OpenAI-compatible HTTP client."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading
import unittest
from unittest.mock import patch

from agent_harness.config import AppConfig
from agent_harness.llm import LLMError, LocalLLMClient


class MockHandler(BaseHTTPRequestHandler):
    last_payload = None
    response_content = "Local mock answer"
    redirect_location = None
    redirect_target_hits = 0

    def do_POST(self) -> None:
        if self.path == "/v1/chat/completions" and type(self).redirect_location:
            self.send_response(307)
            self.send_header("Location", type(self).redirect_location)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path == "/redirect-target":
            type(self).redirect_target_hits += 1
        length = int(self.headers.get("Content-Length", "0"))
        type(self).last_payload = json.loads(self.rfile.read(length).decode("utf-8"))
        body = json.dumps(
            {"choices": [{"message": {"content": type(self).response_content}}]}
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string: str, *args: object) -> None:
        return None


class LocalLLMClientTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.config = AppConfig(
            base_url="http://127.0.0.1:{}/v1".format(self.server.server_port),
            model="test-model",
            timeout_seconds=2,
            data_dir=Path("test-agent-data"),
            config_path=Path("test-config.json"),
        )
        MockHandler.last_payload = None
        MockHandler.response_content = "Local mock answer"
        MockHandler.redirect_location = None
        MockHandler.redirect_target_hits = 0

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_posts_chat_request_and_extracts_answer(self) -> None:
        client = LocalLLMClient(self.config)
        answer = client.complete("system rules", "user question")
        self.assertEqual(answer, "Local mock answer")
        self.assertEqual(MockHandler.last_payload["model"], "test-model")
        self.assertEqual(
            MockHandler.last_payload["messages"],
            [
                {"role": "system", "content": "system rules"},
                {"role": "user", "content": "user question"},
            ],
        )

    def test_ignores_environment_proxy_for_loopback_request(self) -> None:
        proxy = "http://192.0.2.1:9"
        with patch.dict(os.environ, {"http_proxy": proxy, "HTTP_PROXY": proxy}):
            answer = LocalLLMClient(self.config).complete("system", "task")
        self.assertEqual(answer, "Local mock answer")

    def test_rejects_unconfigured_model(self) -> None:
        config = AppConfig(
            base_url=self.config.base_url,
            model="",
            timeout_seconds=2,
            data_dir=self.config.data_dir,
            config_path=self.config.config_path,
        )
        with self.assertRaisesRegex(LLMError, "model is not configured"):
            LocalLLMClient(config).complete("system", "task")

    def test_refuses_redirect_without_sending_prompt_to_target(self) -> None:
        MockHandler.redirect_location = "http://127.0.0.1:{}/redirect-target".format(
            self.server.server_port
        )
        with self.assertRaisesRegex(LLMError, "HTTP 307"):
            LocalLLMClient(self.config).complete("private system prompt", "private task")
        self.assertEqual(MockHandler.redirect_target_hits, 0)

    def test_rejects_oversized_response(self) -> None:
        with patch("agent_harness.llm.MAX_RESPONSE_BYTES", 32):
            with self.assertRaisesRegex(LLMError, "1 MiB limit"):
                LocalLLMClient(self.config).complete("system", "task")


if __name__ == "__main__":
    unittest.main()
