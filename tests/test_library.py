"""Tests for Phase 3 frozen-library integrity and deterministic retrieval."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from agent_harness.library import FrozenLibrary, LibraryError, snapshot_id_for_sources
from tools import freeze_library
from agent_harness import library as library_module

import time

import threading

import stat



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
    snapshot_id = snapshot_id_for_sources(sources)
    (root / "manifest.json").write_text(
        json.dumps(
            {
                "schema_version": "1.0",
                "snapshot_id": snapshot_id,
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
    return FrozenLibrary(root, expected_snapshot_id=snapshot_id)


class FrozenLibraryTests(unittest.TestCase):
    def test_existing_library_requires_an_expected_snapshot_pin(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "library"
            make_library(root)
            with self.assertRaisesRegex(LibraryError, "expected snapshot id"):
                FrozenLibrary(root).search("tree fern indirect light")

    def test_returns_relevant_passage_and_traceable_reference(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            library = make_library(Path(temp_dir) / "library")
            snapshot_id, passages = library.search(
                "According to the guide, what light does a tree fern need?"
            )
            self.assertRegex(snapshot_id, r"^snapshot-[0-9a-f]{16}$")
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

    def test_rejects_root_mode_change_during_descriptor_based_read(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "library"
            library = make_library(root)
            expected_snapshot_id, _ = library.search("tree fern indirect light")
            real_read = os.read
            changed = [False]

            def make_root_writable(fd: int, count: int) -> bytes:
                if not changed[0]:
                    changed[0] = True
                    os.chmod(str(root), 0o755)
                return real_read(fd, count)

            try:
                with patch("agent_harness.library.os.read", side_effect=make_root_writable):
                    with self.assertRaisesRegex(LibraryError, "writable|changed during validation"):
                        FrozenLibrary(root, expected_snapshot_id=expected_snapshot_id).search(
                            "tree fern indirect light"
                        )
            finally:
                os.chmod(str(root), 0o555)

    def test_rejects_root_directory_identity_replacement_during_read(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "library"
            library = make_library(root)
            expected_snapshot_id, _ = library.search("tree fern indirect light")
            moved_root = Path(temp_dir) / "library-original"
            replacement_root = Path(temp_dir) / "library-replacement"
            replacement_root.mkdir()
            os.chmod(str(replacement_root), 0o555)
            real_read = os.read
            replaced = [False]

            def replace_root_path(fd: int, count: int) -> bytes:
                if not replaced[0]:
                    replaced[0] = True
                    root.rename(moved_root)
                    replacement_root.rename(root)
                return real_read(fd, count)

            try:
                with patch("agent_harness.library.os.read", side_effect=replace_root_path):
                    with self.assertRaisesRegex(LibraryError, "changed during validation"):
                        FrozenLibrary(root, expected_snapshot_id=expected_snapshot_id).search(
                            "tree fern indirect light"
                        )
            finally:
                if root.exists():
                    os.chmod(str(root), 0o755)
                    shutil.rmtree(root)
                if moved_root.exists():
                    moved_root.rename(root)

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

    def test_recomputes_snapshot_id_from_all_manifest_source_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "library"
            library = make_library(root)
            manifest_path = root / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["snapshot_id"] = "snapshot-0000000000000000"
            os.chmod(str(manifest_path), 0o644)
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            os.chmod(str(manifest_path), 0o444)
            with self.assertRaisesRegex(LibraryError, "source metadata"):
                library.search("tree fern indirect light")

    def test_configured_pin_rejects_a_self_consistent_rewritten_library(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "library"
            library = make_library(root)
            trusted_snapshot_id, _ = library.search("tree fern indirect light")
            manifest_path = root / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            source = root / manifest["sources"][0]["path"]
            changed_text = "A tree fern needs full sun all day.\n"
            os.chmod(str(source), 0o644)
            source.write_text(changed_text, encoding="utf-8")
            os.chmod(str(source), 0o444)
            manifest["sources"][0]["sha256"] = hashlib.sha256(changed_text.encode("utf-8")).hexdigest()
            manifest["snapshot_id"] = snapshot_id_for_sources(manifest["sources"])
            os.chmod(str(manifest_path), 0o644)
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            os.chmod(str(manifest_path), 0o444)
            pinned = FrozenLibrary(root, expected_snapshot_id=trusted_snapshot_id)
            with self.assertRaisesRegex(LibraryError, "configured pin"):
                pinned.search("tree fern indirect light")



    def test_freezer_rejects_oversized_manifest_before_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "library"
            root.mkdir()
            (root / "guide.md").write_text("A useful local guide.\n", encoding="utf-8")
            with patch("tools.freeze_library.MAX_MANIFEST_BYTES", 64):
                with self.assertRaisesRegex(ValueError, "manifest exceeds the 64 byte limit"):
                    freeze_library.freeze(root)
            self.assertFalse((root / "manifest.json").exists())
            self.assertEqual((root / "guide.md").read_text(encoding="utf-8"), "A useful local guide.\n")


    def test_accepts_a_matching_expected_snapshot_pin(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                library = make_library(Path(temp_dir) / "library")
                pinned_library = FrozenLibrary(
                    library.root,
                    expected_snapshot_id=library.expected_snapshot_id,
                )
                snapshot_id, passages = pinned_library.search("tree fern indirect light")
                self.assertEqual(snapshot_id, library.expected_snapshot_id)
                self.assertEqual(len(passages), 1)

    def test_rejects_a_manifest_snapshot_id_that_mismatches_the_pin(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                library = make_library(Path(temp_dir) / "library")
                pinned_library = FrozenLibrary(
                    library.root,
                    expected_snapshot_id="snapshot-fedcba9876543210",
                )
                with self.assertRaisesRegex(LibraryError, "configured pin"):
                    pinned_library.search("tree fern indirect light")

    def test_rejects_an_invalid_expected_snapshot_pin(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                library = make_library(Path(temp_dir) / "library")
                with self.assertRaisesRegex(ValueError, "expected snapshot id"):
                    FrozenLibrary(library.root, expected_snapshot_id="snapshot-test")

    def test_direct_api_rejects_an_existing_library_without_a_pin(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                library = make_library(Path(temp_dir) / "library")
                unpinned_library = FrozenLibrary(library.root)
                with self.assertRaises(LibraryError) as caught:
                    unpinned_library.search("tree fern indirect light")
                self.assertEqual(caught.exception.reason, "LIBRARY_UNPINNED")

    def test_missing_unpinned_library_remains_unavailable(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                missing_library = FrozenLibrary(Path(temp_dir) / "missing")
                with self.assertRaises(LibraryError) as caught:
                    missing_library.search("tree fern indirect light")
                self.assertEqual(caught.exception.reason, "LIBRARY_UNAVAILABLE")

    def test_pin_existing_works_when_direct_filesystem_root_open_is_denied(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir) / "data" / "data" / "com.termux" / "files" / "home"
            root = home / "project" / "external-data" / "library"
            library = make_library(root)
            previous_cwd = os.getcwd()
            os.chdir(str(home))
            real_open = os.open
            denied_root_opens = []

            def deny_direct_root(path, flags, *args, **kwargs):
                if os.fspath(path) == os.sep and kwargs.get("dir_fd") is None:
                    denied_root_opens.append(os.fspath(path))
                    raise PermissionError(13, "Permission denied", os.sep)
                return real_open(path, flags, *args, **kwargs)

            try:
                with patch("agent_harness.library.os.open", side_effect=deny_direct_root):
                    snapshot_id = freeze_library.pin_existing(root, None)
                self.assertEqual(snapshot_id, library.expected_snapshot_id)
                self.assertEqual(denied_root_opens, [])
            finally:
                os.chdir(previous_cwd)

    def test_absolute_library_path_avoids_denied_intermediate_ancestor(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            protected = Path(temp_dir) / "data" / "data"
            files = protected / "com.termux" / "files"
            termux_tmp = files / "usr" / "tmp" / "pytest-temp"
            home = files / "home"
            root = home / "project" / "external-data" / "library"
            library = make_library(root)
            termux_tmp.mkdir(parents=True)
            previous_cwd = os.getcwd()
            os.chdir(str(termux_tmp))
            real_openat = library_module._openat
            protected_stat = protected.stat()
            denied_ancestor_opens = []

            def deny_protected_parent(directory_fd, name, flags):
                current = os.fstat(directory_fd)
                if (
                    name == ".."
                    and current.st_dev == protected_stat.st_dev
                    and current.st_ino == protected_stat.st_ino
                ):
                    denied_ancestor_opens.append(name)
                    raise PermissionError(13, "Permission denied", "intermediate ancestor")
                return real_openat(directory_fd, name, flags)

            try:
                with patch.object(library_module, "_openat", side_effect=deny_protected_parent):
                    snapshot_id = FrozenLibrary(root).validate_for_pin_recovery()
                self.assertEqual(snapshot_id, library.expected_snapshot_id)
                self.assertEqual(denied_ancestor_opens, [])
            finally:
                os.chdir(previous_cwd)

    def test_absolute_library_path_rejects_parent_component_in_termux_layout(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            protected = Path(temp_dir) / "data" / "data"
            files = protected / "com.termux" / "files"
            termux_tmp = files / "usr" / "tmp" / "pytest-temp"
            project = files / "home" / "project"
            (project / "nested").mkdir(parents=True)
            make_library(project / "external-data" / "library")
            root = project / "nested" / ".." / "external-data" / "library"
            self.assertIn("..", os.fspath(root))
            termux_tmp.mkdir(parents=True)
            previous_cwd = os.getcwd()
            os.chdir(str(termux_tmp))
            real_openat = library_module._openat
            protected_stat = protected.stat()
            denied_ancestor_opens = []

            def deny_protected_parent(directory_fd, name, flags):
                current = os.fstat(directory_fd)
                if (
                    name == ".."
                    and current.st_dev == protected_stat.st_dev
                    and current.st_ino == protected_stat.st_ino
                ):
                    denied_ancestor_opens.append(name)
                    raise PermissionError(13, "Permission denied", "intermediate ancestor")
                return real_openat(directory_fd, name, flags)

            try:
                with patch.object(library_module, "_openat", side_effect=deny_protected_parent):
                    with self.assertRaisesRegex(LibraryError, "absolute frozen library paths must not contain '..'"):
                        FrozenLibrary(root).validate_for_pin_recovery()
                self.assertEqual(denied_ancestor_opens, [])
            finally:
                os.chdir(previous_cwd)

    def test_absolute_library_path_stays_anchored_when_cwd_is_renamed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir) / "home"
            original_cwd = home / "project"
            replacement_cwd = home / "replacement-project"
            moved_cwd = home / "project-original"
            home.mkdir()
            original_cwd.mkdir()
            replacement_cwd.mkdir()
            original_library = make_library(original_cwd / "external-data" / "library")
            replacement_library = make_library(
                replacement_cwd / "external-data" / "library",
                docs=[
                    (
                        "DOC-REPLACEMENT",
                        "replacement.md",
                        "Replacement guide",
                        "This replacement corpus is not the original one.\n",
                    )
                ],
            )
            self.assertNotEqual(
                original_library.expected_snapshot_id,
                replacement_library.expected_snapshot_id,
            )
            previous_cwd = os.getcwd()
            os.chdir(str(original_cwd))
            real_fstat = library_module.os.fstat
            renamed = [False]

            def rename_cwd_after_descriptor_is_pinned(descriptor):
                result = real_fstat(descriptor)
                if not renamed[0]:
                    renamed[0] = True
                    original_cwd.rename(moved_cwd)
                    replacement_cwd.rename(original_cwd)
                return result

            try:
                with patch.object(
                    library_module.os,
                    "fstat",
                    side_effect=rename_cwd_after_descriptor_is_pinned,
                ):
                    snapshot_id = FrozenLibrary(
                        original_cwd / "external-data" / "library"
                    ).validate_for_pin_recovery()
                self.assertTrue(renamed[0])
                self.assertEqual(snapshot_id, original_library.expected_snapshot_id)
            finally:
                os.chdir(previous_cwd)

    def test_relative_library_path_stays_anchored_when_cwd_is_renamed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir) / "home"
            original_cwd = home / "project"
            replacement_cwd = home / "replacement-project"
            moved_cwd = home / "project-original"
            home.mkdir()
            original_cwd.mkdir()
            replacement_cwd.mkdir()
            original_library = make_library(original_cwd / "external-data" / "library")
            replacement_library = make_library(
                replacement_cwd / "external-data" / "library",
                docs=[
                    (
                        "DOC-REPLACEMENT",
                        "replacement.md",
                        "Replacement guide",
                        "This replacement corpus is not the original one.\n",
                    )
                ],
            )
            self.assertNotEqual(
                original_library.expected_snapshot_id,
                replacement_library.expected_snapshot_id,
            )
            previous_cwd = os.getcwd()
            os.chdir(str(original_cwd))
            real_openat = library_module._openat
            renamed = [False]

            def rename_cwd_before_first_path_component(directory_fd, name, flags):
                if not renamed[0]:
                    renamed[0] = True
                    original_cwd.rename(moved_cwd)
                    replacement_cwd.rename(original_cwd)
                return real_openat(directory_fd, name, flags)

            try:
                with patch.object(library_module, "_openat", side_effect=rename_cwd_before_first_path_component):
                    snapshot_id = FrozenLibrary(Path("external-data/library")).validate_for_pin_recovery()
                self.assertTrue(renamed[0])
                self.assertEqual(snapshot_id, original_library.expected_snapshot_id)
            finally:
                os.chdir(previous_cwd)

    def test_recovery_rejects_manifest_id_not_derived_from_source_metadata(self) -> None:
            with tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir) / "library"
                library = make_library(root)
                manifest_path = root / "manifest.json"
                os.chmod(str(root), 0o755)
                os.chmod(str(manifest_path), 0o644)
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                wrong_id = "snapshot-0000000000000000"
                if wrong_id == library.expected_snapshot_id:
                    wrong_id = "snapshot-0000000000000001"
                manifest["snapshot_id"] = wrong_id
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                os.chmod(str(manifest_path), 0o444)
                os.chmod(str(root), 0o555)

                with self.assertRaisesRegex(LibraryError, "source metadata"):
                    FrozenLibrary(root).validate_for_pin_recovery()

    def test_symlink_swap_between_entry_check_and_open_fails_closed(self) -> None:
            for relative in ("manifest.json", "garden/fern.md"):
                with self.subTest(relative=relative), tempfile.TemporaryDirectory() as temp_dir:
                    root = Path(temp_dir) / "library"
                    library = make_library(root)
                    target = root / relative
                    parent_mode = stat.S_IMODE(target.parent.stat().st_mode)
                    original_content = target.read_bytes()
                    outside = Path(temp_dir) / "outside.txt"
                    outside.write_text("outside secret sentinel", encoding="utf-8")
                    real_openat = library_module._openat
                    observed = {}

                    def swap_then_open(directory_fd, name, flags):
                        if name == "garden":
                            observed["directory_flags"] = flags
                        if name == target.name and "file_flags" not in observed:
                            os.chmod(str(target.parent), 0o755)
                            target.unlink()
                            target.symlink_to(outside)
                            os.chmod(str(target.parent), parent_mode)
                            observed["file_flags"] = flags
                        return real_openat(directory_fd, name, flags)

                    try:
                        with patch.object(library_module, "_openat", side_effect=swap_then_open):
                            with self.assertRaises(LibraryError):
                                library.search("tree fern indirect light")
                        self.assertIn("file_flags", observed)
                        self.assertTrue(observed["file_flags"] & os.O_NOFOLLOW)
                        self.assertTrue(observed["file_flags"] & os.O_NONBLOCK)
                        self.assertTrue(observed["directory_flags"] & os.O_DIRECTORY)
                        self.assertTrue(observed["directory_flags"] & os.O_NOFOLLOW)
                        self.assertTrue(observed["directory_flags"] & os.O_NONBLOCK)
                    finally:
                        os.chmod(str(target.parent), 0o755)
                        if target.exists() or target.is_symlink():
                            target.unlink()
                        target.write_bytes(original_content)
                        os.chmod(str(target), 0o444)
                        os.chmod(str(target.parent), parent_mode)

    def test_fifo_replacement_fails_without_blocking(self) -> None:
            for relative in ("manifest.json", "garden/fern.md"):
                with self.subTest(relative=relative), tempfile.TemporaryDirectory() as temp_dir:
                    root = Path(temp_dir) / "library"
                    library = make_library(root)
                    target = root / relative
                    parent_mode = stat.S_IMODE(target.parent.stat().st_mode)
                    original_content = target.read_bytes()
                    real_openat = library_module._openat
                    observed = {}
                    failures = []

                    def swap_then_open(directory_fd, name, flags):
                        if name == target.name and "file_flags" not in observed:
                            os.chmod(str(target.parent), 0o755)
                            target.unlink()
                            os.mkfifo(str(target))
                            os.chmod(str(target.parent), parent_mode)
                            observed["file_flags"] = flags
                        return real_openat(directory_fd, name, flags)

                    def search_library():
                        try:
                            library.search("tree fern indirect light")
                        except Exception as exc:
                            failures.append(exc)

                    thread = threading.Thread(target=search_library, daemon=True)
                    started = time.monotonic()
                    try:
                        with patch.object(library_module, "_openat", side_effect=swap_then_open):
                            thread.start()
                            thread.join(timeout=2.0)
                            returned_promptly = not thread.is_alive()
                            if not returned_promptly:
                                try:
                                    unblock_fd = os.open(str(target), os.O_RDWR | os.O_NONBLOCK)
                                    os.close(unblock_fd)
                                except OSError:
                                    pass
                                thread.join(timeout=1.0)
                        elapsed = time.monotonic() - started
                        self.assertTrue(returned_promptly, "FIFO validation blocked beyond 2 seconds")
                        self.assertLess(elapsed, 2.0)
                        self.assertIsInstance(failures[0] if failures else None, LibraryError)
                        self.assertTrue(observed["file_flags"] & os.O_NOFOLLOW)
                        self.assertTrue(observed["file_flags"] & os.O_NONBLOCK)
                    finally:
                        if thread.is_alive():
                            try:
                                unblock_fd = os.open(str(target), os.O_RDWR | os.O_NONBLOCK)
                                os.close(unblock_fd)
                            except OSError:
                                pass
                            thread.join(timeout=1.0)
                        os.chmod(str(target.parent), 0o755)
                        if target.exists() or target.is_symlink():
                            target.unlink()
                        target.write_bytes(original_content)
                        os.chmod(str(target), 0o444)
                        os.chmod(str(target.parent), parent_mode)

if __name__ == "__main__":
    unittest.main()
