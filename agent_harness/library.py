"""Read-only, hash-verified local library snapshots and lexical retrieval."""
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
from typing import Tuple

MANIFEST_NAME = "manifest.json"
MAX_SOURCE_BYTES = 1024 * 1024
MAX_LIBRARY_BYTES = 16 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_SOURCES = 500
MAX_RESULTS = 5
MAX_EXCERPT_CHARS = 1600
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


class FrozenLibrary:
    """Search a frozen ``data_dir/library`` snapshot without writing to it.

    A library is accepted only when the manifest and every source file are
    non-writable, all files are declared by the manifest, and the recorded
    SHA-256 values match their current contents. The application only opens
    these files for reading; changing a snapshot is an explicit offline action.
    """

    def __init__(self, library_dir: Path):
        self.root = Path(library_dir)

    @staticmethod
    def _tokens(text: str) -> Tuple[str, ...]:
        return tuple(
            token
            for token in _TOKEN_PATTERN.findall(text.casefold())
            if token not in _STOP_WORDS and len(token) > 1
        )

    @staticmethod
    def _ensure_read_only(path: Path, *, directory: bool = False) -> None:
        try:
            mode = stat.S_IMODE(os.lstat(str(path)).st_mode)
        except OSError as exc:
            raise LibraryError("cannot inspect frozen library item: {}".format(path)) from exc
        if mode & 0o222:
            kind = "directory" if directory else "file"
            raise LibraryError("frozen library {} is writable: {}".format(kind, path))

    def _walk(self) -> set:
        if not self.root.exists() or not self.root.is_dir():
            raise LibraryError(
                "frozen library directory is missing: {}".format(self.root),
                reason="LIBRARY_UNAVAILABLE",
            )
        if self.root.is_symlink():
            raise LibraryError("frozen library directory must not be a symlink")
        files = set()
        for current, dir_names, file_names in os.walk(str(self.root), followlinks=False):
            current_path = Path(current)
            self._ensure_read_only(current_path, directory=True)
            for name in list(dir_names):
                child = current_path / name
                if child.is_symlink():
                    raise LibraryError("symbolic links are not allowed in the frozen library")
                if not child.is_dir():
                    raise LibraryError("invalid directory in the frozen library: {}".format(child))
            for name in file_names:
                child = current_path / name
                if child.is_symlink() or not child.is_file():
                    raise LibraryError("only regular files are allowed in the frozen library")
                self._ensure_read_only(child)
                files.add(child.relative_to(self.root).as_posix())
        return files

    def _load_documents(self) -> Tuple[str, Tuple[_LibraryDocument, ...]]:
        files = self._walk()
        manifest_path = self.root / MANIFEST_NAME
        if MANIFEST_NAME not in files:
            raise LibraryError("frozen library manifest is missing")
        try:
            if os.lstat(str(manifest_path)).st_size > MAX_MANIFEST_BYTES:
                raise LibraryError("frozen library manifest exceeds the 1 MiB limit")
            manifest_bytes = manifest_path.read_bytes()
            manifest = json.loads(manifest_bytes.decode("utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise LibraryError("frozen library manifest is unreadable or invalid") from exc
        if not isinstance(manifest, dict) or set(manifest) != {
            "schema_version", "snapshot_id", "sources"
        }:
            raise LibraryError("frozen library manifest has an invalid structure")
        if manifest.get("schema_version") != "1.0":
            raise LibraryError("unsupported frozen library manifest version")
        snapshot_id = manifest.get("snapshot_id")
        raw_sources = manifest.get("sources")
        if not isinstance(snapshot_id, str) or not snapshot_id.strip():
            raise LibraryError("frozen library snapshot id is missing")
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
            source_path = self.root.joinpath(*PurePosixPath(relative_name).parts)
            try:
                resolved = source_path.resolve(strict=True)
                resolved.relative_to(self.root.resolve(strict=True))
                file_stat = os.lstat(str(source_path))
            except (OSError, ValueError) as exc:
                raise LibraryError("frozen library source path is missing or escapes its directory") from exc
            if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_size > MAX_SOURCE_BYTES:
                raise LibraryError("frozen library source is not a bounded regular file")
            total_bytes += file_stat.st_size
            if total_bytes > MAX_LIBRARY_BYTES:
                raise LibraryError("frozen library exceeds the total size limit")
            try:
                raw = source_path.read_bytes()
                text = raw.decode("utf-8")
            except (OSError, UnicodeDecodeError) as exc:
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
        return snapshot_id.strip(), tuple(documents)

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
