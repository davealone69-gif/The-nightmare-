"""Canonical JSONL hash-chain log with full-chain verification."""
from __future__ import annotations
from pathlib import Path
from typing import Any
import hashlib, json, os, threading, time

def _canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()

class SealedLog:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        valid, entries = verify_chain(self.path, return_entries=True)
        if not valid: raise ValueError("existing sealed log is invalid")
        self.prev = entries[-1]["hash"] if entries else "GENESIS"
        self.seq = len(entries)
        self._lock = threading.Lock()

    def anchor_slice(self, start_seq: int, end_seq: int) -> dict[str, Any]:
        """Return a blinded, bounded projection; never expose raw ghost provenance to the anchor."""
        if start_seq < 0 or end_seq < start_seq:
            raise ValueError("invalid anchor slice bounds")
        valid, entries = verify_chain(self.path, return_entries=True)
        if not valid:
            raise ValueError("cannot export an anchor slice from an invalid sealed log")

        hidden = {"role", "source_id", "source_role", "observer_id", "observer", "observer_name",
                  "ghost_id", "ghost", "ghost_name", "queue_id", "component_hash",
                  "source_component_hash", "component_kind", "model_id", "ghost_role",
                  "actor", "artifact_path", "file_path"}

        def blind(value):
            if isinstance(value, dict):
                return {key: ("monitor_flag" if key == "signal" else blind(item))
                        for key, item in value.items() if key not in hidden}
            if isinstance(value, list):
                return [blind(item) for item in value]
            return value

        selected = []
        for entry in entries:
            if not start_seq <= entry["seq"] < end_seq:
                continue
            is_ghost = str(entry.get("actor", "")).startswith("ghost.")
            selected.append({
                "seq": entry["seq"],
                "ts": entry["ts"],
                "actor": "monitor" if is_ghost else entry["actor"],
                "kind": "observation" if is_ghost else entry["kind"],
                "data": blind(entry["data"]),
            })
        projection = {"start_seq": start_seq, "end_seq": end_seq, "entries": selected}
        projection["projection_sha256"] = hashlib.sha256(_canonical(projection)).hexdigest()
        return projection

    def append(self, actor: str, kind: str, data: dict[str, Any]) -> dict[str, Any]:
        if not actor or not kind: raise ValueError("actor and kind are required")
        with self._lock:
            entry = {"seq": self.seq, "ts": time.time(), "actor": actor,
                     "kind": kind, "data": data, "prev": self.prev}
            entry["hash"] = hashlib.sha256(_canonical(entry)).hexdigest()
            with self.path.open("ab") as stream:
                stream.write(_canonical(entry) + b"
")
                stream.flush()
                os.fsync(stream.fileno())
            self.prev, self.seq = entry["hash"], self.seq + 1
            return entry

def verify_chain(path: str | Path, return_entries: bool = False):
    entries = []
    prev = "GENESIS"
    try:
        with Path(path).open("rb") as stream:
            for expected_seq, raw in enumerate(stream):
                if not raw.endswith(b"
"): return (False, entries) if return_entries else False
                entry = json.loads(raw)
                if entry.get("seq") != expected_seq or entry.get("prev") != prev: return (False, entries) if return_entries else False
                claimed = entry.pop("hash", None)
                actual = hashlib.sha256(_canonical(entry)).hexdigest()
                if claimed != actual: return (False, entries) if return_entries else False
                entry["hash"] = claimed
                prev = claimed
                entries.append(entry)
    except (OSError, ValueError, json.JSONDecodeError):
        return (False, entries) if return_entries else False
    return (True, entries) if return_entries else True
