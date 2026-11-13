"""Command-line entry point for one local agent episode."""

import argparse
from pathlib import Path
import sys
from typing import Optional, Sequence

from .config import ConfigError, load_config
from .llm import LLMError, LocalLLMClient
from .orchestrator import Orchestrator
from .worker import GeneralWorker


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="local-agent",
        description="Run one task through the local single-worker harness.",
    )
    parser.add_argument(
        "--config",
        type=Path,
        help="JSON config path (default: config.json beside the project root)",
    )
    parser.add_argument(
        "--base-url",
        help="override the local OpenAI-compatible base URL",
    )
    parser.add_argument("--model", help="local model identifier required by the server")
    parser.add_argument("task", nargs="+", help="task text to send to the local model")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    config_path = args.config or (Path(__file__).resolve().parent.parent / "config.json")
    try:
        config = load_config(
            config_path,
            base_url_override=args.base_url,
            model_override=args.model,
        )
        if not config.model:
            raise ConfigError("set model in config.json or pass --model with a local model id")
        worker = GeneralWorker(LocalLLMClient(config))
        answer = Orchestrator(worker).run_episode(" ".join(args.task))
    except (ConfigError, LLMError, ValueError) as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2
    print(answer)
    return 0
