#!/usr/bin/env python3
"""Create a small external demo corpus for the Phase 3 retrieval example."""
import argparse
from pathlib import Path
import subprocess
import sys

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from agent_harness.config import ConfigError, load_config  # noqa: E402

DEMO_TEXT = """# Demo garden guide: tree fern light

A tree fern grows best in bright, indirect light. Avoid harsh direct afternoon sun.
Keep the potting mix evenly moist, but do not leave the roots standing in water.

This sample text exists only to exercise local retrieval and is not horticultural advice.
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        type=Path,
        default=PROJECT_ROOT / "config.json",
        help="JSON app config (default: config.json in the project root)",
    )
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        library_dir = config.data_dir / "library"
        if library_dir.exists():
            raise ValueError(
                "{} already exists; demo setup will not overwrite a library".format(library_dir)
            )
        library_dir.mkdir(parents=True)
        (library_dir / "demo-fern-guide.md").write_text(DEMO_TEXT, encoding="utf-8")
        freezer = PROJECT_ROOT / "tools" / "freeze_library.py"
        result = subprocess.run(
            [sys.executable, str(freezer), "--config", str(config.config_path)],
            check=False,
        )
        if result.returncode:
            return result.returncode
    except (ConfigError, OSError, ValueError) as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2
    print("demo setup complete; run the cited query shown in README.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
