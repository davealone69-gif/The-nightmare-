"""Declared process/filesystem reset. OS network isolation is injected and verified."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Any
import glob, hashlib, json, os, shutil

DEFAULT_TARGETS = {
    "kv_cache": "~/.nightmare/cache/kv",
    "embedding_cache": "~/.nightmare/cache/embed",
    "tool_logs": "~/.nightmare/logs/tools",
    "tmp": "~/.nightmare/tmp",
    "sqlite_wal": "~/.nightmare/db/*.db-wal",
    "sqlite_shm": "~/.nightmare/db/*.db-shm",
    "rng_state": "~/.nightmare/state/rng",
    "scratch": "~/.nightmare/scratch",
}

@dataclass
class ResetPlan:
    baseline_env: dict[str, str]
    fixed_seed: int
    network_block: Callable[[], bool]
    network_is_blocked: Callable[[], bool]
    restart_model_server: Callable[[], bool]
    expected_weights_hash: str
    query_model_identity_endpoint: Callable[[], dict[str, Any]]
    targets: dict[str, str] | None = None
    episode_hostname: str = "nightmare-local"

def _remove(path: Path) -> None:
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.is_dir():
        shutil.rmtree(path)

def manifest(targets: dict[str, str] | None = None, env: dict[str, str] | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"paths": {}, "env_sha256": ""}
    for name, raw in sorted((targets or DEFAULT_TARGETS).items()):
        pattern = os.path.expanduser(raw)
        entries = []
        for match in sorted(glob.glob(pattern)):
            p = Path(match)
            if p.is_symlink():
                entries.append({"path": match, "type": "symlink", "target": os.readlink(p)})
            elif p.is_file():
                entries.append({"path": match, "type": "file", "sha256": hashlib.sha256(p.read_bytes()).hexdigest()})
            elif p.is_dir():
                digest = hashlib.sha256()
                for child in sorted(p.rglob("*")):
                    rel = str(child.relative_to(p))
                    digest.update(rel.encode())
                    if child.is_symlink(): digest.update(os.readlink(child).encode())
                    elif child.is_file(): digest.update(hashlib.sha256(child.read_bytes()).digest())
                entries.append({"path": match, "type": "dir", "sha256": digest.hexdigest()})
        result["paths"][name] = entries
    env_bytes = json.dumps(dict(sorted((env or {}).items())), sort_keys=True, separators=(",", ":")).encode()
    result["env_sha256"] = hashlib.sha256(env_bytes).hexdigest()
    return result

def total_reset(plan: ResetPlan) -> dict[str, Any]:
    report: dict[str, Any] = {"wiped": [], "failed": [], "env_restored": False,
        "network_blocked": False, "model_server_restarted": False, "model_hash_verified": False, "seeds_fixed": False}
    targets = plan.targets or DEFAULT_TARGETS
    for name, raw in targets.items():
        try:
            for match in glob.glob(os.path.expanduser(raw)):
                _remove(Path(match))
            report["wiped"].append(name)
        except OSError as exc:
            report["failed"].append(f"{name}: {exc}")
    # Build a clean child-process environment; never mutate the host process.
    env = dict(plan.baseline_env)
    env.update({"NM_SEED": str(plan.fixed_seed), "NM_HOST": plan.episode_hostname})
    report["env_restored"] = True
    report["seeds_fixed"] = env["NM_SEED"] == str(plan.fixed_seed)
    try:
        report["network_blocked"] = bool(plan.network_block()) and bool(plan.network_is_blocked())
    except Exception as exc:
        report["failed"].append(f"network isolation: {exc}")
    try:
        report["model_server_restarted"] = bool(plan.restart_model_server())
        # Query the identity endpoint served by the freshly restarted process. A disk hash is not evidence of loaded weights.
        identity = plan.query_model_identity_endpoint() if report["model_server_restarted"] else {}
        reported_hash = identity.get("weights_hash") or identity.get("artifact_hash")
        report["model_identity"] = identity
        report["model_hash_verified"] = (report["model_server_restarted"] and
            bool(plan.expected_weights_hash) and reported_hash == plan.expected_weights_hash)
    except Exception as exc:
        report["failed"].append(f"model server restart: {exc}")
    report["child_env"] = env
    report["post_reset_manifest"] = manifest(targets, env)
    report["complete"] = (not report["failed"] and report["network_blocked"]
                          and report["model_server_restarted"] and report["model_hash_verified"] and report["env_restored"]
                          and report["seeds_fixed"])
    return report

def state_equality_audit(run_suite: Callable[[int, bool], dict[str, Any]], seed: int) -> dict[str, Any]:
    first = run_suite(seed, True)
    second = run_suite(seed, True)
    keys = ("manifest", "outputs")
    differences = {k: {"first": first.get(k), "second": second.get(k)}
                   for k in keys if first.get(k) != second.get(k)}
    if differences:
        raise RuntimeError("RESET LEAK: " + json.dumps(differences, sort_keys=True, default=str))
    return {"passed": True, "seed": seed, "compared": list(keys)}
