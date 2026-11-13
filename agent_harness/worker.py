"""The single, stateless worker used in Phase 1."""

from .llm import LocalLLMClient


class GeneralWorker:
    """Answer one task using a fixed prompt and no external tools."""

    system_prompt = (
        "You are a careful assistant. Answer the user's task clearly and concisely. "
        "If the task cannot be answered reliably, say so plainly. Do not claim to "
        "have used tools or sources that were not provided."
    )
    tool_names = ()

    def __init__(self, client: LocalLLMClient) -> None:
        self._client = client

    def run(self, task: str) -> str:
        if not isinstance(task, str) or not task.strip():
            raise ValueError("task must be a non-empty string")
        return self._client.complete(self.system_prompt, task.strip())
