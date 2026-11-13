"""Tests for configuration loading and validation."""

import json
from pathlib import Path
import tempfile
import unittest

from agent_harness.config import ConfigError, DEFAULT_BASE_URL, load_config


class ConfigTests(unittest.TestCase):
    def write_config(self, directory: Path, **overrides: object) -> Path:
        values = {
            "base_url": DEFAULT_BASE_URL,
            "model": "test-model",
            "timeout_seconds": 3,
            "data_dir": "../data",
        }
        values.update(overrides)
        path = directory / "config.json"
        path.write_text(json.dumps(values), encoding="utf-8")
        return path

    def test_relative_data_dir_resolves_outside_config_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "project"
            root.mkdir()
            config = load_config(self.write_config(root))
            self.assertEqual(config.data_dir, (root / "../data").resolve())
            self.assertEqual(config.model, "test-model")
            self.assertEqual(config.base_url, DEFAULT_BASE_URL)
            self.assertIsNone(config.library_snapshot_id)

    def test_accepts_a_valid_library_snapshot_pin(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            snapshot_id = "snapshot-0123456789abcdef"
            config = load_config(
                self.write_config(root, library_snapshot_id=snapshot_id)
            )
            self.assertEqual(config.library_snapshot_id, snapshot_id)

    def test_rejects_invalid_library_snapshot_pins(self) -> None:
        invalid_values = (
            "snapshot-test",
            "snapshot-0123456789abcde",
            "snapshot-0123456789abcdeG",
            "",
            42,
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for value in invalid_values:
                with self.subTest(value=value):
                    with self.assertRaisesRegex(ConfigError, "library_snapshot_id"):
                        load_config(
                            self.write_config(root, library_snapshot_id=value)
                        )

    def test_rejects_data_dir_inside_project(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            with self.assertRaisesRegex(ConfigError, "outside"):
                load_config(self.write_config(root, data_dir="data"))

    def test_rejects_absolute_data_dir(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            with self.assertRaisesRegex(ConfigError, "relative"):
                load_config(self.write_config(root, data_dir=str(root.parent / "data")))

    def test_rejects_remote_endpoint(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            with self.assertRaisesRegex(ConfigError, "loopback"):
                load_config(self.write_config(root, base_url="https://example.com/v1"))

    def test_applies_command_line_overrides(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            config = load_config(
                self.write_config(root),
                base_url_override="http://localhost:1234/v1",
                model_override="override-model",
            )
            self.assertEqual(config.base_url, "http://localhost:1234/v1")
            self.assertEqual(config.model, "override-model")

    def test_rejects_non_loopback_ip(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            with self.assertRaisesRegex(ConfigError, "loopback"):
                load_config(self.write_config(root, base_url="http://192.0.2.1/v1"))

    def test_rejects_non_finite_timeout(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            with self.assertRaisesRegex(ConfigError, "positive number"):
                load_config(self.write_config(root, timeout_seconds=float("inf")))


if __name__ == "__main__":
    unittest.main()
