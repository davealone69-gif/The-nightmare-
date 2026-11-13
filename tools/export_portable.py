#!/usr/bin/env python3
"""Build a portable project folder and deterministic ZIP archive."""

import argparse
from contextlib import contextmanager
import ctypes
from dataclasses import dataclass
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
from typing import Dict, List, Optional, Sequence, Tuple
import zipfile


PROJECT_ROOT = Path(__file__).resolve().parent.parent
PORTABLE_NAME = "the-nightmare-portable"
MANIFEST_NAME = PORTABLE_NAME + ".manifest.json"
TRANSACTION_NAME = ".nightmare-export-transaction.json"
LOCK_NAME = ".nightmare-export.lock"
STAGING_PREFIX = ".nightmare-export-"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)
FIXED_EPOCH = 315532800
REQUIRED_FILES = ("README.md", "requirements.txt", "config.json", "run.sh")
EXCLUDED_DIR_NAMES = {
    ".git",
    "__pycache__",
    ".cache",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    ".hypothesis",
    ".tox",
    ".nox",
    ".venv",
    "venv",
    "env",
    ".aws",
    ".ssh",
    ".gnupg",
    ".azure",
    ".kube",
    ".docker",
    "gcloud",
    "credentials",
    "secrets",
    "node_modules",
    "tests",
    "test",
    "dist",
    "build",
    "target",
    "logs",
    "log",
    "cache",
    "caches",
    "htmlcov",
    "test-results",
    "test_results",
    "test-artifacts",
    "test_artifacts",
}
EXCLUDED_FILE_NAMES = {
    ".ds_store",
    ".coverage",
    "coverage.xml",
    "junit.xml",
    "nosetests.xml",
}
SENSITIVE_FILE_NAMES = {
    ".npmrc",
    ".pypirc",
    ".netrc",
    ".git-credentials",
    ".dockercfg",
    "credentials",
    "credentials.json",
    "credentials.ini",
    "id_rsa",
    "id_dsa",
    "id_ecdsa",
    "id_ed25519",
    "id_ecdsa_sk",
    "id_ed25519_sk",
}
SENSITIVE_NAME_PATTERN = re.compile(r"(^|[._-])(credentials?|secrets?|tokens?)([._-]|$)")
SECRET_CONTENT_PATTERNS = (
    re.compile(rb"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"),
    re.compile(rb"-----BEGIN PGP (?:PRIVATE|SECRET) KEY BLOCK-----"),
    re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,})\b"),
    re.compile(rb"\bsk-[A-Za-z0-9_-]{20,}\b"),
    re.compile(rb"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{20,}\b"),
)
# This is intentionally a deny policy, not a value-format guess. A non-empty or
# block-style assignment to a credential field is enough to stop the export.
SECRET_FIELD_PATTERN = re.compile(
    rb"(?im)(?:^|[{,])[ \t]*(?:-[ \t]*)?['\"]?"
    rb"([A-Za-z_][A-Za-z0-9_.-]*)['\"]?[ \t]*[:=][ \t]*[^\r\n,}]*"
)
SHELL_EXPORT_PATTERN = re.compile(
    rb"(?im)(?:^|[;&])[ \t]*export[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*="
)
PYTHON_ENVIRONMENT_ASSIGNMENT_PATTERN = re.compile(
    rb"(?i)\bos\.environ\s*\[\s*(['\"])([A-Za-z_][A-Za-z0-9_]*)\1\s*\]\s*="
)
SECRET_FIELD_NAMES = {
    "api_key",
    "access_key",
    "access_key_id",
    "access_token",
    "auth_token",
    "client_secret",
    "credential",
    "credentials",
    "password",
    "passwd",
    "private_key",
    "refresh_token",
    "secret",
    "secret_access_key",
    "secret_key",
    "token",
    "aws_access_key_id",
    "aws_secret_access_key",
    "aws_secret_key",
}


@dataclass(frozen=True)
class SourceFileSnapshot:
    relative: str
    contents: bytes
    executable: bool
    signature: Tuple[int, ...]
    parent_identities: Tuple[Tuple[int, ...], ...]
    content_sha256: str


@dataclass(frozen=True)
class SourceDirectorySnapshot:
    relative: str
    identity: Tuple[int, ...]
    entries: Tuple[Tuple[str, Tuple[int, ...]], ...]


@dataclass(frozen=True)
class ProjectSnapshot:
    root_identity: Tuple[int, ...]
    files: Tuple[SourceFileSnapshot, ...]
    directories: Tuple[SourceDirectorySnapshot, ...]


def _is_within(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
    except ValueError:
        return False
    return True


def _configured_runtime_data_dir(project_root: Path) -> Optional[Path]:
    """Return an in-project configured data directory, if one is present."""
    config_path = project_root / "config.json"
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(config, dict):
        return None
    value = config.get("data_dir")
    if not isinstance(value, str) or not value.strip():
        return None
    candidate = (config_path.parent / value).resolve()
    if _is_within(candidate, project_root):
        return candidate
    return None


def _is_excluded_file(path: Path) -> bool:
    name = path.name.lower()
    suffix = path.suffix.lower()
    if name in EXCLUDED_FILE_NAMES or name in SENSITIVE_FILE_NAMES:
        return True
    if SENSITIVE_NAME_PATTERN.search(name) and suffix not in {".py", ".md", ".rst"}:
        return True
    if name.startswith(".env"):
        return True
    if suffix in {".pyc", ".pyo", ".log", ".tmp", ".swp"}:
        return True
    if name.startswith("test_") and suffix == ".py":
        return True
    if name.endswith("_test.py"):
        return True
    if name.endswith(".log.1") or ".log." in name:
        return True
    if suffix in {".pem", ".key", ".p12", ".pfx"}:
        return True
    return False


def _contains_secret_material(contents: bytes) -> bool:
    """Return true for recognizable secrets or any assignment to a secret field."""
    if any(pattern.search(contents) for pattern in SECRET_CONTENT_PATTERNS):
        return True
    field_names = [match.group(1) for match in SECRET_FIELD_PATTERN.finditer(contents)]
    field_names.extend(match.group(1) for match in SHELL_EXPORT_PATTERN.finditer(contents))
    field_names.extend(
        match.group(2) for match in PYTHON_ENVIRONMENT_ASSIGNMENT_PATTERN.finditer(contents)
    )
    for raw_name in field_names:
        name = raw_name.decode("ascii", errors="ignore")
        if name.isupper() and name.endswith(("_PATTERN", "_PATTERNS", "_FIELD_NAMES")):
            continue
        camel_separated = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
        normalized = re.sub(r"[^a-z0-9]+", "_", camel_separated.lower()).strip("_")
        components = set(normalized.split("_"))
        compact = re.sub(r"[^a-z0-9]", "", normalized)
        if (
            components.intersection({"pattern", "regex", "regexp"})
            and components.intersection({"token"})
            and not components.intersection(
                {"access", "api", "credential", "credentials", "password", "passwd", "private", "secret"}
            )
        ):
            continue
        if normalized in SECRET_FIELD_NAMES:
            return True
        if components.intersection(
            {"credential", "credentials", "password", "passwd", "secret", "token"}
        ):
            return True
        if "key" in components and components.intersection({"access", "api", "private", "secret"}):
            return True
        if compact.startswith("aws") and any(
            token in compact for token in ("secret", "accesskey", "credential", "sessiontoken")
        ):
            return True
        if any(marker in compact for marker in ("secret", "password", "passwd", "credential")):
            return True
    return False


def _identity(info: os.stat_result) -> Tuple[int, ...]:
    return (info.st_dev, info.st_ino, stat.S_IFMT(info.st_mode))


def _signature(info: os.stat_result) -> Tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _nofollow_flags(directory: bool = False) -> int:
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
        raise OSError(errno.ENOTSUP, "safe no-follow source access is unavailable")
    flags = os.O_RDONLY | os.O_NOFOLLOW
    if directory:
        flags |= os.O_DIRECTORY
    return flags


def _open_root(root: Path) -> int:
    return os.open(str(root), _nofollow_flags(directory=True))


def _read_source_snapshot(
    root_fd: int,
    relative: str,
    expected_signature: Optional[Tuple[int, ...]] = None,
    expected_parent_identities: Optional[Tuple[Tuple[int, ...], ...]] = None,
    expected_content_sha256: Optional[str] = None,
) -> SourceFileSnapshot:
    """Read through no-follow directory descriptors and reject observed changes."""
    parts = tuple(part for part in relative.split("/") if part)
    if not parts or any(part in {".", ".."} for part in parts):
        raise ValueError("invalid project-relative source path")

    current_fd = os.dup(root_fd)
    parent_identities = [_identity(os.fstat(root_fd))]
    try:
        for index, component in enumerate(parts[:-1]):
            before = os.stat(component, dir_fd=current_fd, follow_symlinks=False)
            if not stat.S_ISDIR(before.st_mode):
                raise ValueError("source path changed during export: {}".format(relative))
            next_fd = os.open(component, _nofollow_flags(directory=True), dir_fd=current_fd)
            opened = os.fstat(next_fd)
            if _identity(before) != _identity(opened):
                os.close(next_fd)
                raise ValueError("source path changed during export: {}".format(relative))
            parent_identities.append(_identity(opened))
            if expected_parent_identities is not None:
                expected_index = index + 1
                if (
                    expected_index >= len(expected_parent_identities)
                    or parent_identities[-1] != expected_parent_identities[expected_index]
                ):
                    os.close(next_fd)
                    raise ValueError("source path changed during export: {}".format(relative))
            os.close(current_fd)
            current_fd = next_fd

        leaf = parts[-1]
        before = os.stat(leaf, dir_fd=current_fd, follow_symlinks=False)
        if not stat.S_ISREG(before.st_mode):
            raise ValueError("source path changed during export: {}".format(relative))
        if expected_signature is not None and _signature(before) != expected_signature:
            raise ValueError("source content changed during export: {}".format(relative))

        file_fd = os.open(
            leaf,
            os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_NONBLOCK", 0),
            dir_fd=current_fd,
        )
        try:
            opened = os.fstat(file_fd)
            if _signature(opened) != _signature(before):
                raise ValueError("source content changed during export: {}".format(relative))
            chunks = []
            while True:
                chunk = os.read(file_fd, 1024 * 1024)
                if not chunk:
                    break
                chunks.append(chunk)
            contents = b"".join(chunks)
            after_read = os.fstat(file_fd)
            after_path = os.stat(leaf, dir_fd=current_fd, follow_symlinks=False)
            if (
                _signature(after_read) != _signature(before)
                or _signature(after_path) != _signature(before)
                or len(contents) != before.st_size
            ):
                raise ValueError("source content or path changed during export: {}".format(relative))
        finally:
            os.close(file_fd)

        digest = hashlib.sha256(contents).hexdigest()
        if expected_content_sha256 is not None and digest != expected_content_sha256:
            raise ValueError("source content changed during export: {}".format(relative))
        if expected_parent_identities is not None and tuple(parent_identities) != expected_parent_identities:
            raise ValueError("source path changed during export: {}".format(relative))
        return SourceFileSnapshot(
            relative=relative,
            contents=contents,
            executable=bool(before.st_mode & 0o111),
            signature=_signature(before),
            parent_identities=tuple(parent_identities),
            content_sha256=digest,
        )
    except (OSError, FileNotFoundError, NotADirectoryError) as exc:
        raise ValueError("source path changed during export: {}".format(relative)) from exc
    finally:
        os.close(current_fd)


def _directory_state(
    root_fd: int, relative: str
) -> Tuple[Tuple[int, ...], Tuple[Tuple[str, Tuple[int, ...]], ...]]:
    parts = tuple(part for part in relative.split("/") if part)
    current_fd = os.dup(root_fd)
    try:
        for component in parts:
            before = os.stat(component, dir_fd=current_fd, follow_symlinks=False)
            if not stat.S_ISDIR(before.st_mode):
                raise ValueError("source directory changed during export: {}".format(relative))
            next_fd = os.open(component, _nofollow_flags(directory=True), dir_fd=current_fd)
            if _identity(before) != _identity(os.fstat(next_fd)):
                os.close(next_fd)
                raise ValueError("source directory changed during export: {}".format(relative))
            os.close(current_fd)
            current_fd = next_fd
        info = os.fstat(current_fd)
        entries = tuple(
            (name, _identity(os.stat(name, dir_fd=current_fd, follow_symlinks=False)))
            for name in sorted(os.listdir(current_fd))
        )
        return _identity(info), entries
    except OSError as exc:
        raise ValueError("source directory changed during export: {}".format(relative or ".")) from exc
    finally:
        os.close(current_fd)


def discover_project_files(project_root: Path, output_dir: Path) -> ProjectSnapshot:
    """Optimistically capture eligible files and entry identities without following links."""
    root = Path(project_root).resolve(strict=True)
    output = Path(output_dir).resolve()
    runtime_data = _configured_runtime_data_dir(root)
    excluded_roots = [output]
    if runtime_data is not None:
        excluded_roots.append(runtime_data)

    root_fd = _open_root(root)
    files: List[SourceFileSnapshot] = []
    directories: List[SourceDirectorySnapshot] = []
    root_identity = _identity(os.fstat(root_fd))

    def walk(directory_fd: int, relative_dir: str, parent_identities: Tuple[Tuple[int, ...], ...]) -> None:
        names = tuple(sorted(os.listdir(directory_fd)))
        listed_entries = {}
        entry_identities = []
        for name in names:
            relative = name if not relative_dir else relative_dir + "/" + name
            try:
                listed = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            except OSError as exc:
                raise ValueError("source path changed during export: {}".format(relative)) from exc
            listed_entries[name] = listed
            entry_identities.append((name, _identity(listed)))
        directories.append(
            SourceDirectorySnapshot(
                relative_dir, _identity(os.fstat(directory_fd)), tuple(entry_identities)
            )
        )
        for name in names:
            relative = name if not relative_dir else relative_dir + "/" + name
            listed = listed_entries[name]
            child_path = root.joinpath(*relative.split("/"))
            if any(_is_within(child_path, excluded) for excluded in excluded_roots):
                continue
            if stat.S_ISLNK(listed.st_mode):
                continue
            if stat.S_ISDIR(listed.st_mode):
                if (
                    name.lower() in EXCLUDED_DIR_NAMES
                    or SENSITIVE_NAME_PATTERN.search(name.lower())
                ):
                    continue
                child_fd = os.open(name, _nofollow_flags(directory=True), dir_fd=directory_fd)
                try:
                    opened = os.fstat(child_fd)
                    if _identity(listed) != _identity(opened):
                        raise ValueError("source path changed during export: {}".format(relative))
                    walk(child_fd, relative, parent_identities + (_identity(opened),))
                finally:
                    os.close(child_fd)
                continue
            if not stat.S_ISREG(listed.st_mode) or _is_excluded_file(Path(name)):
                continue

            file_snapshot = _read_source_snapshot(
                root_fd,
                relative,
                expected_signature=_signature(listed),
                expected_parent_identities=parent_identities,
            )
            if _contains_secret_material(file_snapshot.contents):
                raise ValueError(
                    "potential secret material in project file; export aborted: {}".format(relative)
                )
            files.append(file_snapshot)

    try:
        walk(root_fd, "", (root_identity,))
    finally:
        os.close(root_fd)
    return ProjectSnapshot(
        root_identity=root_identity,
        files=tuple(sorted(files, key=lambda item: item.relative)),
        directories=tuple(sorted(directories, key=lambda item: item.relative)),
    )


def _verify_project_snapshot(project_root: Path, snapshot: ProjectSnapshot) -> None:
    """Reject observed path, entry-identity, metadata, or content changes since capture.

    This revalidation narrows the race window but does not lock the live source tree.
    """
    root = Path(project_root).resolve(strict=True)
    root_fd = _open_root(root)
    try:
        if _identity(os.fstat(root_fd)) != snapshot.root_identity:
            raise ValueError("project root changed during export")
        root_path_info = os.stat(str(root), follow_symlinks=False)
        if _identity(root_path_info) != snapshot.root_identity:
            raise ValueError("project root path changed during export")
        for directory in snapshot.directories:
            identity, entries = _directory_state(root_fd, directory.relative)
            if identity != directory.identity or entries != directory.entries:
                raise ValueError(
                    "source directory contents or entry identities changed during export: {}".format(
                        directory.relative or "."
                    )
                )
        for original in snapshot.files:
            current = _read_source_snapshot(
                root_fd,
                original.relative,
                expected_signature=original.signature,
                expected_parent_identities=original.parent_identities,
                expected_content_sha256=original.content_sha256,
            )
            if current.content_sha256 != original.content_sha256:
                raise ValueError("source content changed during export: {}".format(original.relative))
    finally:
        os.close(root_fd)


def _tree_digest(root: Path) -> Tuple[str, int]:
    """Hash a regular-file tree using no-follow descriptors; reject all special entries."""
    root_fd = _open_root(root)
    files = []

    def visit(directory_fd: int, relative_dir: str) -> None:
        directory_before = os.fstat(directory_fd)
        names = tuple(sorted(os.listdir(directory_fd)))
        for name in names:
            relative = name if not relative_dir else relative_dir + "/" + name
            try:
                before = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            except OSError as exc:
                raise ValueError(
                    "portable folder changed or contains an unsafe entry: {}".format(relative)
                ) from exc

            if stat.S_ISDIR(before.st_mode):
                try:
                    child_fd = os.open(name, _nofollow_flags(directory=True), dir_fd=directory_fd)
                except OSError as exc:
                    raise ValueError(
                        "portable folder changed or contains an unsafe entry: {}".format(relative)
                    ) from exc
                try:
                    if _identity(before) != _identity(os.fstat(child_fd)):
                        raise ValueError(
                            "portable folder changed during hashing: {}".format(relative)
                        )
                    visit(child_fd, relative)
                finally:
                    os.close(child_fd)
                continue

            if not stat.S_ISREG(before.st_mode):
                raise ValueError(
                    "portable folder contains a symlink or non-regular entry: {}".format(relative)
                )

            try:
                file_fd = os.open(
                    name,
                    os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_NONBLOCK", 0),
                    dir_fd=directory_fd,
                )
            except OSError as exc:
                raise ValueError(
                    "portable folder changed or contains an unsafe entry: {}".format(relative)
                ) from exc
            try:
                opened = os.fstat(file_fd)
                if not stat.S_ISREG(opened.st_mode) or _signature(opened) != _signature(before):
                    raise ValueError("portable folder changed during hashing: {}".format(relative))
                chunks = []
                while True:
                    chunk = os.read(file_fd, 1024 * 1024)
                    if not chunk:
                        break
                    chunks.append(chunk)
                contents = b"".join(chunks)
                after_read = os.fstat(file_fd)
                after_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
                if (
                    _signature(after_read) != _signature(before)
                    or _signature(after_path) != _signature(before)
                    or len(contents) != before.st_size
                ):
                    raise ValueError("portable folder changed during hashing: {}".format(relative))
                files.append((relative, hashlib.sha256(contents).digest()))
            except OSError as exc:
                raise ValueError("portable folder changed during hashing: {}".format(relative)) from exc
            finally:
                os.close(file_fd)

        if tuple(sorted(os.listdir(directory_fd))) != names:
            raise ValueError(
                "portable folder directory changed during hashing: {}".format(relative_dir or ".")
            )
        if _signature(os.fstat(directory_fd)) != _signature(directory_before):
            raise ValueError(
                "portable folder directory changed during hashing: {}".format(relative_dir or ".")
            )

    try:
        visit(root_fd, "")
    finally:
        os.close(root_fd)

    digest = hashlib.sha256()
    for relative, contents_digest in sorted(files, key=lambda item: item[0]):
        encoded = relative.encode("utf-8")
        digest.update(len(encoded).to_bytes(8, byteorder="big"))
        digest.update(encoded)
        digest.update(contents_digest)
    return digest.hexdigest(), len(files)


def _zip_tree_digest(archive_path: Path) -> Tuple[str, int]:
    digest = hashlib.sha256()
    try:
        with zipfile.ZipFile(str(archive_path), mode="r") as archive:
            names = set()
            infos = sorted(archive.infolist(), key=lambda item: item.filename)
            for info in infos:
                name = info.filename
                if info.is_dir() or name.startswith("/") or "\\" in name:
                    raise ValueError("portable ZIP contains an unsafe entry")
                components = name.split("/")
                if any(component in {"", ".", ".."} for component in components) or name in names:
                    raise ValueError("portable ZIP contains an unsafe or duplicate entry")
                names.add(name)
                contents = archive.read(info)
                encoded_name = name.encode("utf-8")
                digest.update(len(encoded_name).to_bytes(8, byteorder="big"))
                digest.update(encoded_name)
                digest.update(hashlib.sha256(contents).digest())
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        raise ValueError("portable ZIP is incomplete or invalid") from exc
    return digest.hexdigest(), len(names)


def _write_bundle(snapshot: ProjectSnapshot, project_root: Path, destination: Path) -> None:
    """Write exactly the bytes that were scanned in the source snapshot."""
    del project_root  # Kept in the helper signature for the existing test seam.
    destination.mkdir(parents=True)
    for source in snapshot.files:
        target = destination.joinpath(*source.relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(
            str(target), os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
            0o755 if source.executable else 0o644,
        )
        try:
            remaining = memoryview(source.contents)
            while remaining:
                written = os.write(descriptor, remaining)
                remaining = remaining[written:]
            os.fchmod(descriptor, 0o755 if source.executable else 0o644)
        finally:
            os.close(descriptor)
        os.utime(str(target), (FIXED_EPOCH, FIXED_EPOCH), follow_symlinks=False)

    directories = [destination]
    directories.extend(path for path in destination.rglob("*") if path.is_dir())
    for directory in sorted(directories, key=lambda item: len(item.parts), reverse=True):
        directory.chmod(0o755)
        os.utime(str(directory), (FIXED_EPOCH, FIXED_EPOCH), follow_symlinks=False)
    _fsync_tree(destination)


def _write_deterministic_zip(bundle: Path, archive_path: Path) -> None:
    files = sorted(
        (path for path in bundle.rglob("*") if path.is_file()),
        key=lambda item: item.relative_to(bundle).as_posix(),
    )
    with zipfile.ZipFile(
        str(archive_path),
        mode="w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        for path in files:
            relative = path.relative_to(bundle).as_posix()
            mode = stat.S_IMODE(path.stat().st_mode)
            info = zipfile.ZipInfo(relative, date_time=FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.extra = b""
            info.comment = b""
            archive.writestr(
                info,
                path.read_bytes(),
                compress_type=zipfile.ZIP_DEFLATED,
                compresslevel=9,
            )
    _fsync_file(archive_path)


def _fsync_file(path: Path) -> None:
    descriptor = os.open(str(path), os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(str(path), _nofollow_flags(directory=True))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _fsync_directory_chain(path: Path) -> None:
    current = Path(path)
    while True:
        _fsync_directory(current)
        if current.parent == current:
            break
        current = current.parent


def _fsync_tree(root: Path) -> None:
    paths = list(root.rglob("*"))
    for path in sorted((item for item in paths if item.is_file()), key=lambda item: item.as_posix()):
        _fsync_file(path)
    directories = [root] + [item for item in paths if item.is_dir()]
    for directory in sorted(directories, key=lambda item: len(item.parts), reverse=True):
        _fsync_directory(directory)


def _path_exists(path: Path) -> bool:
    return path.exists() or path.is_symlink()


def _rename_noreplace(source: Path, destination: Path) -> None:
    """Atomically rename without replacing a competing path (Linux renameat2)."""
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise OSError(errno.ENOTSUP, "safe no-replace publication requires Linux renameat2")
    renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    renameat2.restype = ctypes.c_int
    source_parent = os.open(str(source.parent), _nofollow_flags(directory=True))
    destination_parent = os.open(str(destination.parent), _nofollow_flags(directory=True))
    try:
        result = renameat2(
            source_parent,
            os.fsencode(source.name),
            destination_parent,
            os.fsencode(destination.name),
            1,  # RENAME_NOREPLACE
        )
        if result != 0:
            error = ctypes.get_errno()
            if error in {errno.ENOSYS, errno.EINVAL, getattr(errno, "EOPNOTSUPP", errno.ENOTSUP)}:
                raise OSError(error, "safe no-replace publication is unavailable")
            if error == errno.EEXIST:
                raise FileExistsError(error, os.strerror(error), str(destination))
            raise OSError(error, os.strerror(error), str(destination))
    finally:
        os.close(source_parent)
        os.close(destination_parent)


@contextmanager
def _destination_lock(output: Path):
    """Refuse concurrent cooperating writers using a persistent POSIX lock file."""
    lock_path = output / LOCK_NAME
    flags = os.O_CREAT | os.O_RDWR
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(str(lock_path), flags, 0o600)
    locked = False
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise OSError("portable export lock is not a regular file")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise FileExistsError(
                "another portable export is in progress for this destination"
            ) from exc
        os.fsync(descriptor)
        _fsync_directory(output)
        locked = True
        yield
    finally:
        try:
            if locked:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
        finally:
            os.close(descriptor)


def _matches_file_hash(path: Path, expected: str) -> bool:
    if path.is_symlink() or not path.is_file():
        return False
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return digest == expected


def _matches_tree_hash(path: Path, expected: str) -> bool:
    if path.is_symlink() or not path.is_dir():
        return False
    digest, _ = _tree_digest(path)
    return digest == expected


def _manifest_document(tree_hash: str, archive_hash: str, file_count: int) -> dict:
    return {
        "version": 1,
        "folder": PORTABLE_NAME,
        "archive": PORTABLE_NAME + ".zip",
        "tree_sha256": tree_hash,
        "archive_sha256": archive_hash,
        "file_count": file_count,
    }


def _atomic_write_json_noreplace(output: Path, destination: Path, value: dict) -> None:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=destination.name + ".tmp-", dir=str(output)
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        _rename_noreplace(temporary, destination)
        _fsync_directory(output)
    finally:
        if _path_exists(temporary):
            temporary.unlink()
            _fsync_directory(output)


def _atomic_write_transaction(output: Path, transaction: dict) -> None:
    _atomic_write_json_noreplace(output, output / TRANSACTION_NAME, transaction)


def _atomic_write_manifest(output: Path, manifest: dict) -> None:
    _atomic_write_json_noreplace(output, output / MANIFEST_NAME, manifest)


def _staging_path(output: Path, name: object) -> Optional[Path]:
    if not isinstance(name, str) or Path(name).name != name or not name.startswith(STAGING_PREFIX):
        return None
    return output / name


def _unclaimed_staging_entries(output: Path) -> Tuple[str, ...]:
    """List staging-name entries not authenticated by a durable transaction marker."""
    return tuple(sorted(path.name for path in output.iterdir() if path.name.startswith(STAGING_PREFIX)))


def _remove_staging(path: Optional[Path]) -> None:
    if path is not None and not path.is_symlink() and path.is_dir():
        shutil.rmtree(str(path))


def _quarantine_folder(folder: Path, output: Path, staging: Optional[Path]) -> None:
    quarantine_root = staging
    created_root = False
    if quarantine_root is None or quarantine_root.is_symlink() or not quarantine_root.is_dir():
        quarantine_root = Path(tempfile.mkdtemp(prefix=STAGING_PREFIX + "recovery-", dir=str(output)))
        created_root = True
        _fsync_directory(output)
    quarantine = quarantine_root / "rollback-folder"
    if _path_exists(quarantine):
        quarantine = quarantine_root / "rollback-folder-recovery"
    try:
        _rename_noreplace(folder, quarantine)
        _fsync_directory(output)
        _fsync_directory(quarantine_root)
        shutil.rmtree(str(quarantine))
        _fsync_directory(quarantine_root)
    finally:
        if created_root:
            try:
                shutil.rmtree(str(quarantine_root))
                _fsync_directory(output)
            except OSError:
                pass


def _read_manifest(path: Path) -> Optional[dict]:
    if not _path_exists(path):
        return None
    if path.is_symlink() or not path.is_file():
        raise ValueError("portable export completion manifest is not a regular file")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("portable export completion manifest is invalid") from exc
    if not isinstance(value, dict):
        raise ValueError("portable export completion manifest is invalid")
    return value


def verify_export_pair(output_dir: Path) -> dict:
    """Validate the manifest-gated folder/ZIP pair; consumers must require this."""
    output = Path(output_dir).resolve(strict=True)
    manifest = _read_manifest(output / MANIFEST_NAME)
    if manifest is None:
        raise ValueError("portable export pair is incomplete: completion manifest is missing")
    tree_hash = manifest.get("tree_sha256")
    archive_hash = manifest.get("archive_sha256")
    file_count = manifest.get("file_count")
    if (
        manifest.get("version") != 1
        or manifest.get("folder") != PORTABLE_NAME
        or manifest.get("archive") != PORTABLE_NAME + ".zip"
        or not isinstance(tree_hash, str)
        or not re.fullmatch(r"[0-9a-f]{64}", tree_hash)
        or not isinstance(archive_hash, str)
        or not re.fullmatch(r"[0-9a-f]{64}", archive_hash)
        or not isinstance(file_count, int)
        or isinstance(file_count, bool)
        or file_count < 1
    ):
        raise ValueError("portable export completion manifest is invalid")
    folder = output / PORTABLE_NAME
    archive = output / (PORTABLE_NAME + ".zip")
    if not _matches_tree_hash(folder, tree_hash) or not _matches_file_hash(archive, archive_hash):
        raise ValueError("portable export pair is incomplete or does not match its manifest")
    zip_tree_hash, zip_file_count = _zip_tree_digest(archive)
    folder_tree_hash, folder_file_count = _tree_digest(folder)
    if (
        zip_tree_hash != tree_hash
        or folder_tree_hash != tree_hash
        or zip_file_count != file_count
        or folder_file_count != file_count
    ):
        raise ValueError("portable export folder and ZIP do not form the manifested pair")
    return manifest


def _recover_incomplete_export(output: Path) -> bool:
    """Roll back or finalize artifacts authenticated by the durable transaction marker."""
    marker = output / TRANSACTION_NAME
    if not _path_exists(marker):
        return False
    if marker.is_symlink() or not marker.is_file():
        raise ValueError("portable export transaction marker is not a regular file")
    try:
        transaction = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("portable export transaction marker is invalid; refusing recovery") from exc
    if not isinstance(transaction, dict) or transaction.get("version") not in {1, 2}:
        raise ValueError("portable export transaction marker is unsupported; refusing recovery")
    tree_hash = transaction.get("tree_sha256")
    archive_hash = transaction.get("archive_sha256")
    if not all(
        isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)
        for value in (tree_hash, archive_hash)
    ):
        raise ValueError("portable export transaction marker has invalid hashes; refusing recovery")

    staging = _staging_path(output, transaction.get("staging_dir"))
    folder = output / PORTABLE_NAME
    archive = output / (PORTABLE_NAME + ".zip")
    manifest_path = output / MANIFEST_NAME
    folder_present = _path_exists(folder)
    archive_present = _path_exists(archive)
    folder_matches = folder_present and _matches_tree_hash(folder, tree_hash)
    archive_matches = archive_present and _matches_file_hash(archive, archive_hash)

    if (folder_present and not folder_matches) or (archive_present and not archive_matches):
        raise FileExistsError(
            "portable export recovery found an unexpected output; preserving it and refusing to overwrite"
        )

    if folder_matches and archive_matches:
        folder_tree_hash, file_count = _tree_digest(folder)
        zip_tree_hash, zip_file_count = _zip_tree_digest(archive)
        if folder_tree_hash != tree_hash or zip_tree_hash != tree_hash or zip_file_count != file_count:
            raise ValueError("portable export recovery found a mismatched folder/ZIP pair")
        expected_manifest = _manifest_document(tree_hash, archive_hash, file_count)
        existing_manifest = _read_manifest(manifest_path)
        if existing_manifest is not None and existing_manifest != expected_manifest:
            raise FileExistsError(
                "portable export recovery found an unexpected completion manifest; preserving it"
            )
        if existing_manifest is None:
            _atomic_write_manifest(output, expected_manifest)
        _remove_staging(staging)
        marker.unlink()
        _fsync_directory(output)
        return True

    if _path_exists(manifest_path):
        raise FileExistsError(
            "portable export recovery found a completion manifest without its pair; preserving it"
        )
    if folder_matches:
        _quarantine_folder(folder, output, staging)
    if archive_matches:
        archive.unlink()
        _fsync_directory(output)
    _remove_staging(staging)
    marker.unlink()
    _fsync_directory(output)
    return False


def export_project(project_root: Path, output_dir: Path) -> dict:
    """Create a manifest-gated folder and ZIP; refuse to overwrite existing outputs."""
    root = Path(project_root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("project root must be a directory")

    requested_output = Path(output_dir).expanduser()
    if requested_output.is_symlink():
        raise ValueError("output directory must not be a symbolic link")
    output = requested_output.resolve()
    if output == root:
        raise ValueError("output directory must not be the project root")

    output.mkdir(parents=True, exist_ok=True)
    _fsync_directory_chain(output)
    with _destination_lock(output):
        recovered_complete_pair = _recover_incomplete_export(output)
        folder_path = output / PORTABLE_NAME
        archive_path = output / (PORTABLE_NAME + ".zip")
        manifest_path = output / MANIFEST_NAME
        if recovered_complete_pair:
            manifest = verify_export_pair(output)
            return {
                "folder": folder_path,
                "archive": archive_path,
                "manifest": manifest_path,
                "file_count": manifest["file_count"],
                "tree_sha256": manifest["tree_sha256"],
                "archive_sha256": manifest["archive_sha256"],
            }
        unclaimed_staging = _unclaimed_staging_entries(output)
        if unclaimed_staging:
            raise FileExistsError(
                "unclaimed portable-export staging entry present; preserving it for manual "
                "inspection and removal before retrying: {}".format(", ".join(unclaimed_staging))
            )
        if any(_path_exists(path) for path in (folder_path, archive_path, manifest_path)):
            raise FileExistsError(
                "portable export already exists or is incomplete; choose a new --output-dir to preserve existing files"
            )

        snapshot = discover_project_files(root, output)
        found = {item.relative for item in snapshot.files}
        missing = [name for name in REQUIRED_FILES if name not in found]
        if missing:
            raise ValueError("required project file(s) missing from export: " + ", ".join(missing))

        staging = Path(tempfile.mkdtemp(prefix=STAGING_PREFIX, dir=str(output)))
        _fsync_directory(output)
        bundle = staging / PORTABLE_NAME
        staged_archive = staging / (PORTABLE_NAME + ".zip")
        transaction_written = False
        try:
            _write_bundle(snapshot, root, bundle)
            tree_hash, file_count = _tree_digest(bundle)
            _write_deterministic_zip(bundle, staged_archive)
            _fsync_directory(staging)
            archive_hash = hashlib.sha256(staged_archive.read_bytes()).hexdigest()
            manifest = _manifest_document(tree_hash, archive_hash, file_count)
            transaction = {
                "version": 2,
                "staging_dir": staging.name,
                "tree_sha256": tree_hash,
                "archive_sha256": archive_hash,
                "manifest": manifest,
            }
            _verify_project_snapshot(root, snapshot)
            _atomic_write_transaction(output, transaction)
            transaction_written = True
            try:
                _rename_noreplace(bundle, folder_path)
                _fsync_directory(staging)
                _fsync_directory(output)
                _rename_noreplace(staged_archive, archive_path)
                _fsync_directory(staging)
                _fsync_directory(output)
                _atomic_write_manifest(output, manifest)
            except BaseException as publish_error:
                try:
                    _recover_incomplete_export(output)
                    transaction_written = _path_exists(output / TRANSACTION_NAME)
                except BaseException as recovery_error:
                    transaction_written = True
                    raise OSError(
                        "portable export publication failed and recovery remains pending: {}".format(
                            recovery_error
                        )
                    ) from publish_error
                raise
            (output / TRANSACTION_NAME).unlink()
            _fsync_directory(output)
            transaction_written = False
            verified_manifest = verify_export_pair(output)
            if verified_manifest != manifest:
                raise ValueError("published portable export failed manifest verification")
        finally:
            if not transaction_written and not _path_exists(output / TRANSACTION_NAME):
                _remove_staging(staging)
                _fsync_directory(output)

        return {
            "folder": folder_path,
            "archive": archive_path,
            "manifest": manifest_path,
            "file_count": file_count,
            "tree_sha256": tree_hash,
            "archive_sha256": archive_hash,
        }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=PROJECT_ROOT / "dist",
        help="directory for the portable folder and ZIP (default: project-root/dist)",
    )
    args = parser.parse_args(argv)
    try:
        result = export_project(PROJECT_ROOT, args.output_dir)
    except (FileExistsError, OSError, ValueError) as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2

    print("portable folder: {}".format(result["folder"]))
    print("ZIP archive: {}".format(result["archive"]))
    print("completion manifest: {}".format(result["manifest"]))
    print("files: {}".format(result["file_count"]))
    print("tree sha256: {}".format(result["tree_sha256"]))
    print("ZIP sha256: {}".format(result["archive_sha256"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
