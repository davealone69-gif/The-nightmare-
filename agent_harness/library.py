"""Read-only, hash-verified local library snapshots and lexical retrieval."""
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
from typing import Optional, Tuple

MANIFEST_NAME = "manifest.json"
MAX_SOURCE_BYTES = 1024 * 1024
MAX_LIBRARY_BYTES = 16 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_SOURCES = 500
MAX_RESULTS = 5
MAX_EXCERPT_CHARS = 1600


def snapshot_id_for_sources(sources) -> str:
    """Compute the stable snapshot ID from the manifest's ordered source list."""
    payload = json.dumps(
        sources,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]
    return "snapshot-{}".format(digest)


_TOKEN_PATTERN = re.compile(r"[a-z0-9][a-z0-9_-]*")
_STOP_WORDS = frozenset(
    "a an and are as at be been but by can could did do does for from had has "
    "have he her here hers him his how i if in into is it its may me might "
    "more most my no not of on or our ours she should so than that the their "
    "them then there these they this those through to too up us was we were "
    "what when where which while who why will with would you your yours about "
    "according guide source sources cite citing cited citation citations tell "
    "explain describe summarize summary say says said need needs use used "
    "doesn't don't please local library document documents information"
    .split()
)


class LibraryError(ValueError):
    """A local library is unavailable or its frozen snapshot is invalid."""

    def __init__(self, message: str, reason: str = "LIBRARY_INTEGRITY_FAILURE"):
        super().__init__(message)
        self.reason = reason


@dataclass(frozen=True)
class SourceReference:
    """A stable citation and local-file locator for one frozen source."""

    source_id: str
    title: str
    path: str
    sha256: str

    @property
    def citation(self) -> str:
        return "[{}]".format(self.source_id)


@dataclass(frozen=True)
class RetrievedPassage:
    """A source reference paired with a relevant excerpt from that source."""

    source: SourceReference
    excerpt: str


@dataclass(frozen=True)
class _LibraryDocument:
    reference: SourceReference
    text: str


def _openat(directory_fd: int, name: str, flags: int) -> int:
    """Small seam around descriptor-relative opens for deterministic race tests."""
    return os.open(name, flags, dir_fd=directory_fd)


def _stat_signature(value) -> Tuple[int, ...]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
        value.st_nlink,
    )


class _SafeLibraryTree:
    """Descriptor-anchored snapshot traversal with no-follow bounded reads."""

    def __init__(self, root: Path):
        self.root = Path(root)
        self._fds = []
        self.root_fd = None
        self.directories = {}
        self.directory_stats = {}
        self.directory_names = {}
        self.directory_parents = {}
        self.entries = {}
        self.files = {}
        self.ancestry = []

    @staticmethod
    def _flags(*, directory: bool) -> int:
        required = ["O_NOFOLLOW", "O_NONBLOCK"]
        if directory:
            required.append("O_DIRECTORY")
        if any(not hasattr(os, name) for name in required):
            raise LibraryError("secure no-follow library opens are unavailable")
        flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
        if directory:
            flags |= os.O_DIRECTORY
        flags |= getattr(os, "O_CLOEXEC", 0)
        return flags

    @staticmethod
    def _require_directory(value, description: str) -> None:
        if stat.S_ISLNK(value.st_mode) or not stat.S_ISDIR(value.st_mode):
            raise LibraryError("library path component is not a directory: {}".format(description))

    @staticmethod
    def _require_read_only(value, description: str) -> None:
        if value.st_mode & 0o222:
            raise LibraryError("frozen library item is writable: {}".format(description))

    @staticmethod
    def _same_object(left, right) -> bool:
        return (
            left.st_dev == right.st_dev
            and left.st_ino == right.st_ino
            and stat.S_IFMT(left.st_mode) == stat.S_IFMT(right.st_mode)
        )

    @staticmethod
    def _same_snapshot(left, right) -> bool:
        return _stat_signature(left) == _stat_signature(right)

    @staticmethod
    def _raise_io(message: str, exc: OSError) -> None:
        raise LibraryError("{}: {}".format(message, exc)) from exc

    def _stat_at(self, directory_fd: int, name: str):
        try:
            return os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        except OSError as exc:
            self._raise_io("cannot inspect frozen library entry {}".format(name), exc)

    def _open_root(self) -> None:
        try:
            current_fd = os.open(".", self._flags(directory=True))
            cwd_stat = os.fstat(current_fd)
            if not self._same_object(cwd_stat, os.stat(".", follow_symlinks=False)):
                os.close(current_fd)
                raise LibraryError("current directory changed while opening its descriptor")
        except OSError as exc:
            self._raise_io("cannot open current directory for library validation", exc)
        self._fds.append(current_fd)

        raw_path = os.fspath(self.root)
        absolute_path = os.path.isabs(raw_path)
        if absolute_path:
            # Ascend from the pinned cwd descriptor to the real filesystem root.
            # This avoids opening "/" and does not rely on a cwd string that may
            # become stale if the process directory is renamed concurrently.
            while True:
                current_stat = os.fstat(current_fd)
                try:
                    expected = os.stat("..", dir_fd=current_fd, follow_symlinks=False)
                    if not stat.S_ISDIR(expected.st_mode):
                        raise LibraryError("library path ancestor is not a directory")
                    child_fd = _openat(current_fd, "..", self._flags(directory=True))
                    opened = os.fstat(child_fd)
                except OSError as exc:
                    self._raise_io("cannot securely open frozen library path ancestor", exc)
                if not stat.S_ISDIR(opened.st_mode) or not self._same_snapshot(expected, opened):
                    os.close(child_fd)
                    raise LibraryError("frozen library path ancestor changed while it was being opened")
                if self._same_object(current_stat, opened):
                    os.close(child_fd)
                    break
                self._fds.append(child_fd)
                self.ancestry.append((current_fd, "..", expected, child_fd))
                current_fd = child_fd

        components = [
            part
            for part in raw_path.split(os.sep)
            if part and part != os.curdir
        ]
        for index, component in enumerate(components):
            try:
                expected = os.stat(component, dir_fd=current_fd, follow_symlinks=False)
            except FileNotFoundError as exc:
                raise LibraryError(
                    "frozen library directory is missing: {}".format(self.root),
                    reason="LIBRARY_UNAVAILABLE",
                ) from exc
            except OSError as exc:
                self._raise_io("cannot inspect frozen library path", exc)
            if stat.S_ISLNK(expected.st_mode):
                raise LibraryError("symbolic links are not allowed in the frozen library path")
            if not stat.S_ISDIR(expected.st_mode):
                if index == len(components) - 1:
                    raise LibraryError(
                        "frozen library directory is missing: {}".format(self.root),
                        reason="LIBRARY_UNAVAILABLE",
                    )
                raise LibraryError("library path component is not a directory: {}".format(component))
            try:
                child_fd = _openat(
                    current_fd,
                    component,
                    self._flags(directory=True),
                )
                opened = os.fstat(child_fd)
            except OSError as exc:
                self._raise_io("cannot securely open frozen library path", exc)
            if not stat.S_ISDIR(opened.st_mode) or not self._same_snapshot(expected, opened):
                os.close(child_fd)
                raise LibraryError("frozen library path changed while it was being opened")
            self._fds.append(child_fd)
            self.ancestry.append((current_fd, component, expected, child_fd))
            current_fd = child_fd

        self.root_fd = current_fd

    def _scan_directory(self, relative: str, directory_fd: int) -> None:
        try:
            opened_directory = os.fstat(directory_fd)
            self._require_directory(opened_directory, relative or str(self.root))
            self._require_read_only(opened_directory, relative or str(self.root))
            names = tuple(sorted(os.listdir(directory_fd)))
        except OSError as exc:
            self._raise_io("cannot list frozen library directory", exc)
        self.directories[relative] = directory_fd
        self.directory_stats[relative] = opened_directory
        self.directory_names[relative] = names

        for name in names:
            if name in ("", ".", "..") or "/" in name:
                raise LibraryError("invalid entry name in frozen library")
            entry = self._stat_at(directory_fd, name)
            self.entries[(relative, name)] = entry
            child_relative = name if not relative else relative + "/" + name
            if stat.S_ISLNK(entry.st_mode):
                raise LibraryError("symbolic links are not allowed in the frozen library")
            if stat.S_ISDIR(entry.st_mode):
                self._require_read_only(entry, child_relative)
                try:
                    child_fd = _openat(
                        directory_fd,
                        name,
                        self._flags(directory=True),
                    )
                    opened = os.fstat(child_fd)
                except OSError as exc:
                    self._raise_io("cannot securely open frozen library directory", exc)
                if not stat.S_ISDIR(opened.st_mode) or not self._same_snapshot(entry, opened):
                    os.close(child_fd)
                    raise LibraryError("frozen library directory changed while it was being opened")
                self._fds.append(child_fd)
                self.directory_parents[child_relative] = (directory_fd, name, entry, child_fd)
                self._scan_directory(child_relative, child_fd)
            elif stat.S_ISREG(entry.st_mode):
                self._require_read_only(entry, child_relative)
                self.files[child_relative] = (directory_fd, name, entry)
            else:
                raise LibraryError("only regular files and directories are allowed in the frozen library")

    def scan(self) -> None:
        self._open_root()
        if self.root_fd is None:
            raise LibraryError(
                "frozen library directory is missing: {}".format(self.root),
                reason="LIBRARY_UNAVAILABLE",
            )
        self._scan_directory("", self.root_fd)

    def read_file(self, relative: str, max_bytes: int) -> bytes:
        item = self.files.get(relative)
        if item is None:
            raise LibraryError("frozen library file is missing: {}".format(relative))
        directory_fd, name, expected = item
        if expected.st_size > max_bytes:
            raise LibraryError("frozen library file exceeds its size limit: {}".format(relative))
        try:
            file_fd = _openat(directory_fd, name, self._flags(directory=False))
        except OSError as exc:
            self._raise_io("cannot securely open frozen library file {}".format(relative), exc)
        try:
            opened = os.fstat(file_fd)
            if not stat.S_ISREG(opened.st_mode):
                raise LibraryError("frozen library entry is not a regular file: {}".format(relative))
            self._require_read_only(opened, relative)
            if not self._same_snapshot(expected, opened):
                raise LibraryError("frozen library file changed before it was read: {}".format(relative))
            if opened.st_size > max_bytes:
                raise LibraryError("frozen library file exceeds its size limit: {}".format(relative))

            content = bytearray()
            while True:
                remaining = max_bytes + 1 - len(content)
                chunk = os.read(file_fd, min(65536, remaining))
                if not chunk:
                    break
                content.extend(chunk)
                if len(content) > max_bytes:
                    raise LibraryError("frozen library file exceeds its size limit: {}".format(relative))

            after_read = os.fstat(file_fd)
            current_entry = self._stat_at(directory_fd, name)
            if (
                not self._same_snapshot(opened, after_read)
                or not self._same_snapshot(expected, current_entry)
                or len(content) != opened.st_size
            ):
                raise LibraryError("frozen library file changed while it was being read: {}".format(relative))
            return bytes(content)
        except OSError as exc:
            self._raise_io("cannot read frozen library file {}".format(relative), exc)
        finally:
            os.close(file_fd)

    def verify_unchanged(self) -> None:
        for relative, directory_fd in self.directories.items():
            try:
                current_directory = os.fstat(directory_fd)
                current_names = tuple(sorted(os.listdir(directory_fd)))
            except OSError as exc:
                self._raise_io("cannot revalidate frozen library directory", exc)
            if (
                not self._same_snapshot(self.directory_stats[relative], current_directory)
                or current_names != self.directory_names[relative]
            ):
                raise LibraryError("frozen library directory changed during validation")
            for name in self.directory_names[relative]:
                current_entry = self._stat_at(directory_fd, name)
                if not self._same_snapshot(self.entries[(relative, name)], current_entry):
                    raise LibraryError("frozen library entry changed during validation: {}".format(name))

        for parent_fd, name, expected, child_fd in self.directory_parents.values():
            current_entry = self._stat_at(parent_fd, name)
            current_directory = os.fstat(child_fd)
            if (
                not self._same_snapshot(expected, current_entry)
                or not self._same_snapshot(expected, current_directory)
            ):
                raise LibraryError("frozen library directory identity changed during validation")

        for parent_fd, name, expected, opened_fd in self.ancestry:
            current_entry = self._stat_at(parent_fd, name)
            opened_directory = os.fstat(opened_fd)
            if (
                not self._same_snapshot(expected, current_entry)
                or not self._same_snapshot(expected, opened_directory)
            ):
                raise LibraryError("frozen library path changed during validation")

    def close(self) -> None:
        for descriptor in reversed(self._fds):
            try:
                os.close(descriptor)
            except OSError:
                pass
        self._fds.clear()


class FrozenLibrary:
    """Search a frozen ``data_dir/library`` snapshot without writing to it.

    A library is accepted only when the manifest and every source file are
    non-writable, all files are declared by the manifest, and the recorded
    SHA-256 values match their current contents. Existing libraries require an
    expected snapshot ID; missing libraries remain ``LIBRARY_UNAVAILABLE``.
    The application only opens these files for reading; changing a snapshot is
    an explicit offline action.
    """

    def __init__(
        self,
        library_dir: Path,
        expected_snapshot_id: Optional[str] = None,
    ):
        self.root = Path(library_dir)
        if expected_snapshot_id is not None and (
            not isinstance(expected_snapshot_id, str)
            or not re.fullmatch(r"snapshot-[0-9a-f]{16}", expected_snapshot_id)
        ):
            raise ValueError("expected snapshot id is invalid")
        self.expected_snapshot_id = expected_snapshot_id

    @staticmethod
    def _tokens(text: str) -> Tuple[str, ...]:
        return tuple(
            token
            for token in _TOKEN_PATTERN.findall(text.casefold())
            if token not in _STOP_WORDS and len(token) > 1
        )

    def _load_documents(
        self,
        *,
        require_expected_pin: bool = True,
    ) -> Tuple[str, Tuple[_LibraryDocument, ...]]:
        tree = _SafeLibraryTree(self.root)
        try:
            tree.scan()
            if self.expected_snapshot_id is None and require_expected_pin:
                raise LibraryError(
                    "frozen library requires an expected snapshot id",
                    reason="LIBRARY_UNPINNED",
                )
            files = set(tree.files)
            if MANIFEST_NAME not in files:
                raise LibraryError("frozen library manifest is missing")
            try:
                manifest_bytes = tree.read_file(MANIFEST_NAME, MAX_MANIFEST_BYTES)
                manifest = json.loads(manifest_bytes.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise LibraryError("frozen library manifest is unreadable or invalid") from exc
            if not isinstance(manifest, dict) or set(manifest) != {
                "schema_version", "snapshot_id", "sources"
            }:
                raise LibraryError("frozen library manifest has an invalid structure")
            if manifest.get("schema_version") != "1.0":
                raise LibraryError("unsupported frozen library manifest version")
            snapshot_id = manifest.get("snapshot_id")
            raw_sources = manifest.get("sources")
            if not isinstance(snapshot_id, str) or not re.fullmatch(
                r"snapshot-[0-9a-f]{16}", snapshot_id
            ):
                raise LibraryError("frozen library snapshot id is invalid")
            if not isinstance(raw_sources, list) or not raw_sources or len(raw_sources) > MAX_SOURCES:
                raise LibraryError("frozen library must contain between 1 and {} sources".format(MAX_SOURCES))

            documents = []
            source_ids = set()
            declared_paths = {MANIFEST_NAME}
            total_bytes = 0
            for item in raw_sources:
                if not isinstance(item, dict) or set(item) != {"id", "title", "path", "sha256"}:
                    raise LibraryError("frozen library source entry has an invalid structure")
                source_id, title, relative_name, expected_hash = (
                    item.get("id"), item.get("title"), item.get("path"), item.get("sha256")
                )
                if (
                    not isinstance(source_id, str)
                    or not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,63}", source_id)
                    or source_id in source_ids
                ):
                    raise LibraryError("frozen library source ids must be unique uppercase identifiers")
                if not isinstance(title, str) or not title.strip() or len(title) > 300:
                    raise LibraryError("frozen library source title is invalid")
                if (
                    not isinstance(relative_name, str)
                    or "\\" in relative_name
                    or PurePosixPath(relative_name).is_absolute()
                    or any(part in ("", ".", "..") for part in PurePosixPath(relative_name).parts)
                    or PurePosixPath(relative_name).suffix.lower() not in (".md", ".txt")
                ):
                    raise LibraryError("frozen library source path is invalid")
                if relative_name in declared_paths:
                    raise LibraryError("frozen library source paths must be unique")
                if not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
                    raise LibraryError("frozen library source hash is invalid")
                declared_paths.add(relative_name)
                source_ids.add(source_id)
                raw = tree.read_file(relative_name, MAX_SOURCE_BYTES)
                total_bytes += len(raw)
                if total_bytes > MAX_LIBRARY_BYTES:
                    raise LibraryError("frozen library exceeds the total size limit")
                try:
                    text = raw.decode("utf-8")
                except UnicodeDecodeError as exc:
                    raise LibraryError("frozen library source is not readable UTF-8") from exc
                actual_hash = hashlib.sha256(raw).hexdigest()
                if actual_hash != expected_hash:
                    raise LibraryError("frozen library source digest does not match its manifest")
                if not text.strip():
                    raise LibraryError("frozen library source is empty")
                reference = SourceReference(
                    source_id=source_id,
                    title=title.strip(),
                    path=relative_name,
                    sha256=actual_hash,
                )
                documents.append(_LibraryDocument(reference=reference, text=text))

            if files != declared_paths:
                raise LibraryError("frozen library files do not exactly match the manifest")
            computed_snapshot_id = snapshot_id_for_sources(raw_sources)
            if computed_snapshot_id != snapshot_id:
                raise LibraryError(
                    "frozen library snapshot id does not match its source metadata"
                )
            if (
                self.expected_snapshot_id is not None
                and computed_snapshot_id != self.expected_snapshot_id
            ):
                raise LibraryError(
                    "frozen library snapshot id does not match the configured pin"
                )
            tree.verify_unchanged()
            return computed_snapshot_id, tuple(documents)
        finally:
            tree.close()

    def validate_for_pin_recovery(self) -> str:
        """Validate an unpinned existing snapshot and return its computed ID.

        This explicit recovery-only path returns no source content. Ordinary
        searches on an unpinned library remain blocked with ``LIBRARY_UNPINNED``.
        """
        if self.expected_snapshot_id is not None:
            raise ValueError("pin recovery requires an unpinned library validator")
        snapshot_id, _ = self._load_documents(require_expected_pin=False)
        return snapshot_id

    @staticmethod
    def _excerpt(text: str, terms: set) -> str:
        selected = []
        seen = set()
        for line in text.splitlines():
            clean = " ".join(line.split())
            if not clean:
                continue
            key = clean.casefold()
            if key in seen or not (set(FrozenLibrary._tokens(clean)) & terms):
                continue
            seen.add(key)
            selected.append(clean)
            if sum(len(value) + 1 for value in selected) >= MAX_EXCERPT_CHARS:
                break
        excerpt = "\n".join(selected)[:MAX_EXCERPT_CHARS].strip()
        if not excerpt:
            excerpt = "\n".join(" ".join(text.splitlines())[:MAX_EXCERPT_CHARS].split())
        return excerpt

    def search(self, query: str) -> Tuple[str, Tuple[RetrievedPassage, ...]]:
        """Return ``(snapshot_id, passages)`` ordered by lexical relevance."""
        if not isinstance(query, str) or not query.strip():
            raise ValueError("query must be a non-empty string")
        snapshot_id, documents = self._load_documents()
        query_terms = set(self._tokens(query))
        if not query_terms:
            return snapshot_id, ()
        ranked = []
        for document in documents:
            text_terms = set(self._tokens(document.reference.title + " " + document.text))
            matched = query_terms & text_terms
            if not matched:
                continue
            ranked.append(
                (
                    -len(matched) / len(query_terms),
                    -len(matched),
                    document.reference.source_id,
                    document,
                    matched,
                )
            )
        ranked.sort(key=lambda item: item[:3])
        results = tuple(
            RetrievedPassage(
                source=document.reference,
                excerpt=self._excerpt(document.text, matched),
            )
            for _, _, _, document, matched in ranked[:MAX_RESULTS]
        )
        return snapshot_id, results
