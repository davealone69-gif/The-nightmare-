"""Command-line entry point for one local three-worker episode."""

import argparse
import json
from pathlib import Path
import sys
from typing import Optional, Sequence

from .config import ConfigError, load_config
from .library import FrozenLibrary
from .llm import LLMError, LocalLLMClient
from .orchestrator import CitationStatus, EpisodeStatus, OrchestrationError, Orchestrator
from .reset_state import ResetAuditError
from .review import ReviewError, ReviewStore, build_observation_event, open_monitor_writer
from .worker import Analyst, Formatter, Retriever

REVIEW_COMMANDS = {"queue", "show", "approve", "reject", "edit", "next"}
MONITOR_COMMANDS = {"observe"}


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
    parser.add_argument(
        "--audit-replay",
        action="store_true",
        help="run two same-task, same-seed episodes and compare declared harness state",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=0,
        help="seed recorded for replay comparison; not forwarded to the model server",
    )
    parser.add_argument(
        "--review-data-dir",
        type=Path,
        help="external Phase 5 data directory (defaults to config data_dir)",
    )
    parser.add_argument(
        "--next-episode-from",
        help="include only human-approved/reviewed context from this prior episode",
    )
    parser.add_argument(
        "--episode-id",
        help="ID for this episode; required with --next-episode-from",
    )
    parser.add_argument("task", nargs="+", help="task text to send to the local model")
    return parser


def _review_data_dir(config_path: Optional[Path], override: Optional[Path]) -> Path:
    if override is not None:
        return override.expanduser().resolve()
    return load_config(config_path).data_dir


def _json_output(value: object) -> None:
    print(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2))


def review_main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="local-agent review",
        description="Human-visible Phase 5 monitor review and next-episode queue.",
    )
    subparsers = parser.add_subparsers(dest="review_command", required=True)

    def common(command_parser: argparse.ArgumentParser) -> None:
        command_parser.add_argument("--config", type=Path, help="harness config path")
        command_parser.add_argument("--data-dir", type=Path, help="external data directory override")

    queue_parser = subparsers.add_parser("queue", help="list pending or all monitor observations")
    common(queue_parser)
    queue_parser.add_argument("--episode-id")
    queue_parser.add_argument("--include-reviewed", action="store_true")

    show_parser = subparsers.add_parser("show", help="show one item with full machine/human provenance")
    common(show_parser)
    show_parser.add_argument("observation_id")

    for action in ("approve", "reject", "edit"):
        action_parser = subparsers.add_parser(action, help="record an explicit human decision")
        common(action_parser)
        action_parser.add_argument("observation_id")
        action_parser.add_argument("--reviewer-id", required=True, help="accountable human name or ID")
        if action == "reject":
            action_parser.add_argument("--reason", required=True, help="human-authored rejection reason")
        else:
            action_parser.add_argument("--reason", default="", help="human-authored decision rationale")
        if action == "edit":
            action_parser.add_argument("--edit-text", required=True, help="human-authored replacement guidance")

    next_parser = subparsers.add_parser("next", help="prepare auditable human-reviewed context for a next episode")
    common(next_parser)
    next_parser.add_argument("--source-episode", required=True)
    next_parser.add_argument("--next-episode", required=True)

    args = parser.parse_args(list(argv))
    try:
        data_dir = _review_data_dir(args.config, args.data_dir)
        with ReviewStore(data_dir) as store:
            if args.review_command == "queue":
                items = store.list_queue(episode_id=args.episode_id, include_reviewed=args.include_reviewed)
                _json_output({
                    "monitoring_disclosed": True,
                    "streams": {"mandela": "Mandela semantic monitor", "matrix": "Matrix mechanism monitor"},
                    "items": items,
                })
            elif args.review_command == "show":
                item = store.show_item(args.observation_id)
                _json_output({"monitoring_disclosed": True, "item": item})
            elif args.review_command in {"approve", "reject", "edit"}:
                event = store.decide(
                    args.observation_id,
                    action=args.review_command,
                    reviewer_id=args.reviewer_id,
                    reason=args.reason,
                    edit_text=getattr(args, "edit_text", ""),
                )
                _json_output({"decision": event, "next_episode_effect": "use local-agent review next or --next-episode-from"})
            elif args.review_command == "next":
                _json_output(store.prepare_next_episode(args.source_episode, args.next_episode))
        return 0
    except (ConfigError, ReviewError, OSError, ValueError) as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2


def monitor_main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="local-agent monitor",
        description="Append one structured monitor observation to its own write-only stream.",
    )
    subparsers = parser.add_subparsers(dest="monitor_command", required=True)
    observe = subparsers.add_parser("observe", help="append a machine-authored signal")
    observe.add_argument("--config", type=Path, help="harness config path")
    observe.add_argument("--data-dir", type=Path, help="external data directory override")
    observe.add_argument("--stream", choices=("mandela", "matrix"), required=True)
    observe.add_argument("--episode-id", required=True)
    observe.add_argument("--signal-code", required=True)
    observe.add_argument("--severity", choices=("P0", "P1", "P2", "P3"), required=True)
    observe.add_argument("--confidence", type=float, required=True)
    observe.add_argument("--coverage", type=float)
    observe.add_argument("--summary", required=True)
    observe.add_argument("--component-version", required=True)
    observe.add_argument("--evidence-ref", action="append", default=[])
    args = parser.parse_args(list(argv))
    try:
        data_dir = _review_data_dir(args.config, args.data_dir)
        event = build_observation_event(
            stream_id=args.stream,
            episode_id=args.episode_id,
            signal_code=args.signal_code,
            severity=args.severity,
            confidence=args.confidence,
            coverage=args.coverage,
            summary=args.summary,
            evidence_refs=args.evidence_ref,
            component_version=args.component_version,
        )
        with open_monitor_writer(data_dir, args.stream) as writer:
            writer.append(event)
        _json_output({"observation": event, "provenance_disclosure": "Machine-authored signal; pending human review."})
        return 0
    except (ConfigError, ReviewError, OSError, ValueError) as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2


def main(argv: Optional[Sequence[str]] = None) -> int:
    actual_argv = list(sys.argv[1:] if argv is None else argv)
    if actual_argv and actual_argv[0] == "--phase5":
        command_argv = actual_argv[1:]
        if len(command_argv) >= 2 and command_argv[0] == "review" and command_argv[1] in REVIEW_COMMANDS:
            return review_main(command_argv[1:])
        if len(command_argv) >= 2 and command_argv[0] == "monitor" and command_argv[1] in MONITOR_COMMANDS:
            return monitor_main(command_argv[1:])
        print("error: --phase5 expects an explicit review or monitor subcommand", file=sys.stderr)
        return 2
    args = build_parser().parse_args(actual_argv)
    config_path = args.config or (Path(__file__).resolve().parent.parent / "config.json")
    try:
        config = load_config(
            config_path,
            base_url_override=args.base_url,
            model_override=args.model,
        )
        if not config.model:
            raise ConfigError("set model in config.json or pass --model with a local model id")
        library_dir = config.data_dir / "library"
        if (library_dir.exists() or library_dir.is_symlink()) and config.library_snapshot_id is None:
            raise ConfigError("frozen library is not pinned; set library_snapshot_id in config.json")
        if bool(args.next_episode_from) != bool(args.episode_id):
            raise ValueError("--next-episode-from and --episode-id must be provided together")
        task = " ".join(args.task)
        if args.next_episode_from:
            with ReviewStore(args.review_data_dir or config.data_dir) as review_store:
                prepared = review_store.prepare_next_episode(args.next_episode_from, args.episode_id)
                task = ReviewStore.build_next_episode_task(task, prepared)
        client = LocalLLMClient(config)
        library = FrozenLibrary(
            library_dir,
            expected_snapshot_id=config.library_snapshot_id,
        )
        orchestrator = Orchestrator(
            Retriever(client, library),
            Analyst(client),
            Formatter(client),
        )
        if args.audit_replay:
            replay_audit = orchestrator.run_replay_audit(task, seed=args.seed)
            result = replay_audit.episode_result
        else:
            replay_audit = None
            result = orchestrator.run_episode(task)
    except (ConfigError, LLMError, OrchestrationError, ResetAuditError, ReviewError, OSError, ValueError) as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2

    if replay_audit is not None:
        print("replay_audit: PASS")
        print("declared_state_sha256: {}".format(replay_audit.state_sha256))
        print(
            "worker_output_fingerprints_equal: {} "
            "(informational; excluded from declared-state equality)".format(
                "yes" if replay_audit.worker_output_fingerprints_equal else "no"
            )
        )
        print("reset_audit: PASS (both episodes)")
    if args.episode_id:
        print("episode_id: {}".format(args.episode_id))
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
