"""Tests for Phase 3 frozen-library integrity and deterministic retrieval."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

from agent_harness.library import FrozenLibrary, LibraryError


def make_library(root: Path, docs=None) -> FrozenLibrary:
    root.mkdir(parents=True)
    sources = []
    for source_id, filename, title, text in docs or [
        (
            "DOC-FERN-GUIDE",
            "garden/fern.md",
            "Tree fern guide",
            "# Tree fern guide\nA tree fern grows best in bright, indirect light.\n",
        )
    ]:
        path = root / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = text.encode("utf-8")
        path.write_bytes(raw)
        sources.append(
            {
                "id": source_id,
                "title": title,
                "path": filename,
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
        )
    (root / "manifest.json").write_text(
        json.dumps(
            {
                "schema_version": "1.0",
                "snapshot_id": "snapshot-test",
                "sources": sources,
            }
        ),
        encoding="utf-8",
    )
    for path in root.rglob("*"):
        if path.is_file():
            os.chmod(str(path), 0o444)
    for path in sorted((item for item in root.rglob("*") if item.is_dir()), reverse=True):
        os.chmod(str(path), 0o555)
    os.chmod(str(root), 0o555)
    return FrozenLibrary(root)


class FrozenLibraryTests(unittest.TestCase):
    def test_returns_relevant_passage_and_traceable_reference(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            library = make_library(Path(temp_dir) / "library")
            snapshot_id, passages = library.search(
                "According to the guide, what light does a tree fern need?"
            )
            self.assertEqual(snapshot_id, "snapshot-test")
            self.assertEqual(len(passages), 1)
            self.assertEqual(passages[0].source.citation, "[DOC-FERN-GUIDE]")
            self.assertEqual(passages[0].source.path, "garden/fern.md")
            self.assertIn("bright, indirect light", passages[0].excerpt)
            self.assertRegex(passages[0].source.sha256, r"^[0-9a-f]{64}$")

    def test_returns_no_passages_for_an_unmatched_query(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            library = make_library(Path(temp_dir) / "library")
            _, passages = library.search("Where are the Andes mountains?")
            self.assertEqual(passages, ())

    def test_detects_source_mutation_against_manifest_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "library"
            library = make_library(root)
            source = root / "garden" / "fern.md"
            os.chmod(str(source), 0o644)
            source.write_text("Changed after snapshot.\n", encoding="utf-8")
            os.chmod(str(source), 0o444)
            with self.assertRaisesRegex(LibraryError, "digest"):
                library.search("tree fern indirect light")

    def test_rejects_a_writable_snapshot_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "library"
            library = make_library(root)
            os.chmod(str(root), 0o755)
            with self.assertRaisesRegex(LibraryError, "writable"):
                library.search("tree fern indirect light")

    def test_rejects_unlisted_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "library"
            library = make_library(root)
            os.chmod(str(root), 0o755)
            (root / "unindexed.txt").write_text("Not in the manifest", encoding="utf-8")
            os.chmod(str(root / "unindexed.txt"), 0o444)
            os.chmod(str(root), 0o555)
            with self.assertRaisesRegex(LibraryError, "exactly match"):
                library.search("tree fern indirect light")


if __name__ == "__main__":
    unittest.main()
