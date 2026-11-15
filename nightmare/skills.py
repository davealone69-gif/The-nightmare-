"""Immutable, content-addressed skill capsules; promotion is always explicit."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, Any
import hashlib, json, os

SkillKind = Literal["prompt", "tool_policy", "routing_rule", "retrieval_policy"]
def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()

@dataclass(frozen=True)
class SkillCapsule:
    name: str
    version: int
    kind: SkillKind
    body: dict[str, Any]
    parent: str | None = None

    @property
    def hash(self) -> str:
        return hashlib.sha256(canonical({"name": self.name, "version": self.version,
            "kind": self.kind, "body": self.body, "parent": self.parent})).hexdigest()

class SkillLibrary:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.mkdir(parents=True, exist_ok=True)
        self._caps: dict[str, SkillCapsule] = {}
        self._approved: set[str] = set()
        for file in self.path.glob("*.json"):
            data = json.loads(file.read_text(encoding="utf-8"))
            cap = SkillCapsule(**data["capsule"])
            if cap.hash != data["hash"] or file.stem != cap.hash: raise ValueError(f"capsule integrity failure: {file}")
            self._caps[cap.hash] = cap
        approvals = self.path / "approvals.jsonl"
        if approvals.exists():
            for line in approvals.read_text(encoding="utf-8").splitlines():
                record = json.loads(line)
                if record.get("approved") is True and record.get("hash") in self._caps:
                    self._approved.add(record["hash"])

    def add(self, capsule: SkillCapsule) -> SkillCapsule:
        if capsule.version < 1 or not capsule.name: raise ValueError("invalid capsule identity")
        if capsule.parent is not None and capsule.parent not in self._caps: raise KeyError("unknown parent capsule")
        digest = capsule.hash
        target = self.path / f"{digest}.json"
        if target.exists(): raise FileExistsError(target)
        record = {"hash": digest, "capsule": {"name": capsule.name, "version": capsule.version,
            "kind": capsule.kind, "body": capsule.body, "parent": capsule.parent}, "approved": False}
        with target.open("x", encoding="utf-8") as stream:
            json.dump(record, stream, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
            stream.flush(); os.fsync(stream.fileno())
        self._caps[digest] = capsule
        return capsule

    def load(self, name: str, version: int) -> SkillCapsule:
        for cap in self._caps.values():
            if cap.name == name and cap.version == version: return cap
        raise KeyError((name, version))

    def evolve(self, parent_hash: str, body: dict[str, Any]) -> SkillCapsule:
        parent = self._caps[parent_hash]
        child = SkillCapsule(parent.name, parent.version + 1, parent.kind, body, parent_hash)
        return self.add(child)

    def approve(self, capsule_hash: str, *, human_authorized: bool, tests_passed: bool) -> None:
        if not human_authorized or not tests_passed: raise PermissionError("human approval and passing comparison are required")
        if capsule_hash not in self._caps: raise KeyError(capsule_hash)
        path = self.path / f"{capsule_hash}.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        if data["approved"]: return
        # Approval is a separate append-only event, not a rewrite of the capsule.
        approvals = self.path / "approvals.jsonl"
        with approvals.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"hash": capsule_hash, "approved": True}, sort_keys=True) + "\n")
            stream.flush(); os.fsync(stream.fileno())
        self._approved.add(capsule_hash)

    @property
    def approved_hashes(self) -> set[str]: return set(self._approved)

def identity(weights_hash: str, capsules: list[SkillCapsule]) -> dict[str, Any]:
    if not weights_hash: raise ValueError("weights_hash is required")
    hashes = sorted(c.hash for c in capsules)
    return {"weights_hash": weights_hash,
            "skills_hash": hashlib.sha256(canonical(hashes)).hexdigest(),
            "skill_capsules": hashes}
