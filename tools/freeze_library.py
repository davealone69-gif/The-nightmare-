#!/usr/bin/env python3
"""Create and permission-lock a manifest for ``data_dir/library`` sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
from typing import Optional, Sequence

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from agent_harness.config import ConfigError, load_config  # noqa: E402
from agent_harness.library import (  # noqa: E402
    MAX_LIBRARY_BYTES,
    MAX_MANIFEST_BYTES,
    MAX_SOURCE_BYTES,
    MAX_SOURCES,
    FrozenLibrary,
    MANIFEST_NAME,
    snapshot_id_for_sources,
)


def _source_title(text: str, path: Path) -> str:
    for line in text.splitlines():
        heading = re.match(r"^#\s+(.+?)\s*#*\s*$", line.strip())
        if heading:
            return heading.group(1).strip()[:300]
    return path.stem[:300]


def _source_id(relative_path: str) -> str:
    slug = re.sub(r"[^A-Z0-9]+", "-", relative_path.upper()).strip("-")
    return "DOC-{}".format(slug[:60]).rstrip("-")


def freeze(
    library_dir: Path,
    expected_snapshot_id: Optional[str] = None,
    *,
    allow_repin: bool = False,
) -> str:
    """Create a first manifest and lock sources; refuses to replace a snapshot."""
    root = Path(library_dir)
    if root.exists() and root.is_symlink():
        raise ValueError("library directory must not be a symbolic link")
    root.mkdir(parents=True, exist_ok=True)
    manifest_path = root / MANIFEST_NAME
    if manifest_path.exists():
        raise ValueError("a frozen manifest already exists; refusing to replace it")

    source_paths = []
    for current, dir_names, file_names in os.walk(str(root), followlinks=False):
        current_path = Path(current)
        for name in list(dir_names):
            child = current_path / name
            if child.is_symlink():
                raise ValueError("symbolic links are not allowed in the library")
        for name in file_names:
            path = current_path / name
            if path.is_symlink() or not path.is_file():
                raise ValueError("only regular text files are allowed in the library")
            if path.name == MANIFEST_NAME and path == manifest_path:
                continue
            if path.suffix.lower() not in (".md", ".txt"):
                raise ValueError("only .md and .txt source files are allowed: {}".format(path.name))
            source_paths.append(path)
    if not source_paths:
        raise ValueError("no .md or .txt source files found in {}".format(root))
    if len(source_paths) > MAX_SOURCES:
        raise ValueError("too many source files (maximum {})".format(MAX_SOURCES))

    sources = []
    used_ids = set()
    total_size = 0
    for path in sorted(source_paths, key=lambda item: item.relative_to(root).as_posix()):
        relative_path = path.relative_to(root).as_posix()
        raw = path.read_bytes()
        if len(raw) > MAX_SOURCE_BYTES:
            raise ValueError("source file exceeds 1 MiB: {}".format(relative_path))
        total_size += len(raw)
        if total_size > MAX_LIBRARY_BYTES:
            raise ValueError("library exceeds the 16 MiB source-size limit")
        text = raw.decode("utf-8")
        if not text.strip():
            raise ValueError("source file is empty: {}".format(relative_path))
        source_id = _source_id(relative_path)
        if not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,63}", source_id) or source_id in used_ids:
            raise ValueError("source paths produce invalid or duplicate citation ids")
        used_ids.add(source_id)
        sources.append(
            {
                "id": source_id,
                "title": _source_title(text, path),
                "path": relative_path,
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
        )

    snapshot_id = snapshot_id_for_sources(sources)
    if (
        expected_snapshot_id is not None
        and snapshot_id != expected_snapshot_id
        and not allow_repin
    ):
        raise ValueError("new library snapshot differs from the configured pin; pass --repin to replace it")
    manifest = {
        "schema_version": "1.0",
        "snapshot_id": snapshot_id,
        "sources": sources,
    }
    serialized = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    serialized_bytes = serialized.encode("utf-8")
    if len(serialized_bytes) > MAX_MANIFEST_BYTES:
        raise ValueError(
            "serialized library manifest exceeds the {} byte limit".format(
                MAX_MANIFEST_BYTES
            )
        )
    descriptor = os.open(str(manifest_path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(serialized_bytes)
        output.flush()
        os.fsync(output.fileno())

    for path in source_paths:
        os.chmod(str(path), stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH)
    os.chmod(str(manifest_path), stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH)
    directories = [Path(current) for current, _, _ in os.walk(str(root), topdown=False)]
    for directory in directories:
        os.chmod(str(directory), stat.S_IRUSR | stat.S_IXUSR | stat.S_IRGRP | stat.S_IXGRP | stat.S_IROTH | stat.S_IXOTH)
    return snapshot_id


def pin_existing(
    library_dir: Path,
    expected_snapshot_id: Optional[str],
    *,
    allow_repin: bool = False,
) -> str:
    """Validate and return a snapshot, preserving any pre-existing config pin."""
    snapshot_id = FrozenLibrary(library_dir).validate_for_pin_recovery()
    if (
        expected_snapshot_id is not None
        and snapshot_id != expected_snapshot_id
        and not allow_repin
    ):
        raise ValueError(
            "configured library pin does not match the existing snapshot; "
            "refusing to replace it without --repin"
        )
    return snapshot_id


def _write_config_pin(config_path: Path, snapshot_id: str, *, allow_repin: bool = False) -> None:
    """Atomically store the trusted snapshot ID outside the library directory."""
    config_path = Path(config_path)
    raw_config = json.loads(config_path.read_text(encoding="utf-8"))
    if not isinstance(raw_config, dict):
        raise ValueError("config file must contain a JSON object")
    old_pin = raw_config.get("library_snapshot_id")
    if old_pin is not None and old_pin != snapshot_id and not allow_repin:
        raise ValueError("refusing to replace the configured library pin without --repin")
    raw_config["library_snapshot_id"] = snapshot_id
    serialized = json.dumps(raw_config, ensure_ascii=False, indent=2) + "\n"
    current_mode = stat.S_IMODE(config_path.stat().st_mode)
    descriptor, temp_name = tempfile.mkstemp(prefix=".config-pin-", dir=str(config_path.parent))
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(serialized)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temp_name, current_mode)
        os.replace(temp_name, str(config_path))
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        type=Path,
        default=PROJECT_ROOT / "config.json",
        help="JSON app config (default: config.json in the project root)",
    )
    parser.add_argument(
        "--pin-existing",
        action="store_true",
        help="validate an existing frozen library and pin its ID without modifying its files",
    )
    parser.add_argument(
        "--repin",
        action="store_true",
        help="explicitly replace an existing library_snapshot_id in config.json",
    )
    args = parser.parse_args(argv)
    try:
        config = load_config(args.config)
        library_dir = config.data_dir / "library"
        snapshot_id = (
            pin_existing(
                library_dir,
                config.library_snapshot_id,
                allow_repin=args.repin,
            )
            if args.pin_existing
            else freeze(
                library_dir,
                expected_snapshot_id=config.library_snapshot_id,
                allow_repin=args.repin,
            )
        )
        _write_config_pin(config.config_path, snapshot_id, allow_repin=args.repin)
    except (ConfigError, OSError, UnicodeDecodeError, ValueError) as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2
    print("frozen library: {}".format(library_dir))
    print("snapshot_id: {}".format(snapshot_id))
    print("config pin: {}".format(config.config_path))
    print("sources: read-only; update by preparing a new library snapshot")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
