"""Command-line entry point for one local three-worker episode."""

import argparse
from pathlib import Path
import sys
from typing import Optional, Sequence

from .config import ConfigError, load_config
from .library import FrozenLibrary
from .llm import LLMError, LocalLLMClient
from .orchestrator import CitationStatus, EpisodeStatus, OrchestrationError, Orchestrator
from .worker import Analyst, Formatter, Retriever


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="local-agent",
        description="Run one task through local Retriever, Analyst, and Formatter workers.",
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
        client = LocalLLMClient(config)
        library = FrozenLibrary(config.data_dir / "library")
        result = Orchestrator(
            Retriever(client, library),
            Analyst(client),
            Formatter(client),
        ).run_episode(" ".join(args.task))
    except (ConfigError, LLMError, OrchestrationError, ValueError) as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2

    print("status: {}".format(result.status.value))
    print("confidence: {:.2f}".format(result.confidence))
    print("workers: {}".format(" -> ".join(item.role for item in result.worker_results)))
    if result.status is EpisodeStatus.ABSTAINED:
        print("abstaining_worker: {}".format(result.abstaining_worker))
        print("reason: {}".format(result.reason))
        return 0

    print("citation_status: {}".format(result.citation_status.value))
    if result.citation_status is CitationStatus.UNCITED:
        print("warning: ANSWER_NOT_CITED")
    elif result.citation_status is CitationStatus.INVALID:
        print("warning: INVALID_CITATION")
    if result.worker_results[-1].extractive_fallback:
        print("answer_mode: EXTRACTIVE_FALLBACK")
    print("answer:")
    print(result.answer)
    if result.source_refs:
        print("sources:")
        for source in result.source_refs:
            print(
                "- {} {} — {} (sha256:{})".format(
                    source.citation,
                    source.title,
                    source.path,
                    source.sha256,
                )
            )
    return 0
