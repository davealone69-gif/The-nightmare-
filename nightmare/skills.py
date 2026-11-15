"""Immutable, content-addressed skill capsules; promotion is always explicit."""
from __future__ import annotations
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from collections.abc import Mapping
from typing import Literal, Any
import hashlib, json, os

SkillKind = Literal["prompt", "tool_policy", "routing_rule", "retrieval_policy"]


def _freeze(value: Any) -> Any:
    if isinstance(value, Mapping):
        if any(not isinstance(key, str) for key in value):
            raise TypeError("skill capsule mapping keys must be strings")
        return MappingProxyType({key: _freeze(item) for key, item in value.items()})
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(item) for item in value)
    if value is None or isinstance(value, (str, int, bool)):
        return value
    if isinstance(value, float):
        if not (float("-inf") < value < float("inf")):
            raise ValueError("non-finite numbers are not valid capsule values")
        return value
    raise TypeError(f"unsupported skill capsule value: {type(value).__name__}")


def _thaw(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _thaw(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [_thaw(item) for item in value]
    return value


def canonical(value: Any) -> bytes:
    return json.dumps(_thaw(value), sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def _capsule_hash(name: str, version: int, kind: str, body: Any, parent: str | None) -> str:
    material = {"name": name, "version": version, "kind": kind,
                "body": _thaw(body), "parent": parent}
    return hashlib.sha256(canonical(material)).hexdigest()


@dataclass(frozen=True)
class SkillCapsule:
    name: str
    version: int
    kind: SkillKind
    body: Mapping[str, Any]
    parent: str | None = None
    hash: str = field(init=False)

    def __post_init__(self):
        if self.kind not in {"prompt", "tool_policy", "routing_rule", "retrieval_policy"}:
            raise ValueError("invalid skill capsule kind")
        if self.version < 1 or not self.name:
            raise ValueError("invalid skill capsule identity")
        frozen = _freeze(self.body)
        object.__setattr__(self, "body", frozen)
        object.__setattr__(self, "hash", _capsule_hash(
            self.name, self.version, self.kind, frozen, self.parent))

    def validate(self) -> None:
        current = _capsule_hash(self.name, self.version, self.kind, self.body, self.parent)
        if current != self.hash:
            raise ValueError("skill capsule hash drift detected")


class SkillLibrary:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.mkdir(parents=True, exist_ok=True)
        self._caps: dict[str, SkillCapsule] = {}
        self._approved: set[str] = set()
        for file in sorted(self.path.glob("*.json"), key=lambda p: p.name):
            data = json.loads(file.read_text(encoding="utf-8"))
            cap = SkillCapsule(**data["capsule"])
            cap.validate()
            if cap.hash != data["hash"] or file.stem != cap.hash:
                raise ValueError(f"capsule integrity failure: {file.name}")
            self._caps[cap.hash] = cap
        approvals = self.path / "approvals.jsonl"
        if approvals.exists():
            for line in approvals.read_text(encoding="utf-8").splitlines():
                record = json.loads(line)
                if record.get("approved") is True and record.get("hash") in self._caps:
                    self._approved.add(record["hash"])

    def add(self, capsule: SkillCapsule) -> SkillCapsule:
        capsule.validate()
        if capsule.parent is not None and capsule.parent not in self._caps:
            raise KeyError("unknown parent capsule")
        target = self.path / f"{capsule.hash}.json"
        if target.exists():
            raise FileExistsError(target)
        record = {"hash": capsule.hash, "capsule": {
            "name": capsule.name, "version": capsule.version, "kind": capsule.kind,
            "body": _thaw(capsule.body), "parent": capsule.parent}, "approved": False}
        with target.open("x", encoding="utf-8") as stream:
            json.dump(record, stream, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        self._caps[capsule.hash] = capsule
        return capsule

    def load(self, name: str, version: int) -> SkillCapsule:
        for cap in self._caps.values():
            if cap.name == name and cap.version == version:
                cap.validate()
                return cap
        raise KeyError((name, version))

    def evolve(self, parent_hash: str, body: dict[str, Any]) -> SkillCapsule:
        parent = self._caps[parent_hash]
        parent.validate()
        return self.add(SkillCapsule(parent.name, parent.version + 1,
                                     parent.kind, body, parent_hash))

    def approve(self, capsule_hash: str, *, human_authorized: bool, tests_passed: bool) -> None:
        if not human_authorized or not tests_passed:
            raise PermissionError("human approval and passing comparison are required")
        if capsule_hash not in self._caps:
            raise KeyError(capsule_hash)
        self._caps[capsule_hash].validate()
        if capsule_hash in self._approved:
            return
        approvals = self.path / "approvals.jsonl"
        with approvals.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"hash": capsule_hash, "approved": True},
                                    sort_keys=True, separators=(",", ":")) + "\\n")
            stream.flush()
            os.fsync(stream.fileno())
        self._approved.add(capsule_hash)

    @property
    def approved_hashes(self) -> set[str]:
        return set(self._approved)


def identity(weights_hash: str, capsules: list[SkillCapsule]) -> dict[str, Any]:
    if not weights_hash:
        raise ValueError("weights_hash is required")
    for capsule in capsules:
        capsule.validate()
    hashes = sorted({capsule.hash for capsule in capsules})
    return {"weights_hash": weights_hash,
            "skills_hash": hashlib.sha256(canonical(hashes)).hexdigest(),
            "skill_capsules": hashes}
