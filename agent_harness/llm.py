"""Small HTTP client for a local OpenAI-compatible chat completion server."""

import json
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from .config import AppConfig


class LLMError(RuntimeError):
    """Raised when the local model server cannot return a usable answer."""


MAX_RESPONSE_BYTES = 1024 * 1024


class _NoRedirectHandler(HTTPRedirectHandler):
    """Refuse redirects so prompts cannot be forwarded to another host."""

    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class LocalLLMClient:
    """Send one chat-completion request to the configured local server."""

    def __init__(self, config: AppConfig) -> None:
        self._config = config
        self._opener = build_opener(ProxyHandler({}), _NoRedirectHandler())

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        if not self._config.model:
            raise LLMError("model is not configured; set model in config.json or use --model")
        if not system_prompt.strip() or not user_prompt.strip():
            raise ValueError("system_prompt and user_prompt must not be empty")

        payload = {
            "model": self._config.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "stream": False,
        }
        endpoint = self._config.base_url.rstrip("/") + "/chat/completions"
        request = Request(
            endpoint,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )

        try:
            with self._opener.open(request, timeout=self._config.timeout_seconds) as response:
                response_bytes = response.read(MAX_RESPONSE_BYTES + 1)
        except HTTPError as exc:
            raise LLMError(
                "local model server returned HTTP {}".format(exc.code)
            ) from exc
        except URLError as exc:
            reason = getattr(exc, "reason", "connection failed")
            if isinstance(reason, TimeoutError):
                raise LLMError("timed out waiting for the local model server") from exc
            raise LLMError("could not connect to the local model server: {}".format(reason)) from exc
        except TimeoutError as exc:
            raise LLMError("timed out waiting for the local model server") from exc

        if len(response_bytes) > MAX_RESPONSE_BYTES:
            raise LLMError("local model server response exceeds the 1 MiB limit")
        try:
            response_body = response_bytes.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise LLMError("local model server response is not valid UTF-8") from exc

        try:
            result = json.loads(response_body)
        except json.JSONDecodeError as exc:
            raise LLMError("local model server returned invalid JSON") from exc

        try:
            content = result["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise LLMError("local model server response has no chat message content") from exc
        if not isinstance(content, str) or not content.strip():
            raise LLMError("local model server returned empty or non-text content")
        return content.strip()
