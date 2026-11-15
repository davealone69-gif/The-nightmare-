# THE NIGHTMARE — Code-side implementation contract

**Status: SPECIFICATION COPIED; IMPLEMENTATION NOT YET VERIFIED.**  
Schema version: 0.2  
This document preserves the owner's supplied code-side design alongside the NIGHTMARE prompt. It is the implementation companion, not evidence that these components already exist or work.

## 2. THE CONTRACT (foundation before agents)

Every component, including ghosts, speaks the same envelope. This is what makes the system auditable.

```python
# contracts.py
from dataclasses import dataclass, field, asdict
from typing import Literal, Optional, Any
import hashlib, json, time, uuid

SCHEMA_VERSION = "0.2"

@dataclass
class Output:
    """Every worker, critic, and ghost returns one of these. No free text."""
    run_id: str
    role: str                          # "worker.analyst", "ghost.mandela", etc.
    status: Literal["ok", "abstain", "error"]
    payload: Optional[dict] = None     # schema-validated per role
    confidence: float = 0.0            # 0.0–1.0, self-reported, never trusted alone
    reasons: list[str] = field(default_factory=list)
    abstain_reason: Optional[str] = None
    model_id: str = ""                 # human-readable
    weights_hash: str = ""             # sha256 of weights file — the real pin
    skill_capsules: list[str] = field(default_factory=list)  # hashes of loaded skills
    seed: int = 0
    created_at: float = 0.0

    def validate(self) -> list[str]:
        errs = []
        if self.status == "ok" and self.payload is None:
            errs.append("status=ok requires payload")
        if self.status == "abstain" and not self.abstain_reason:
            errs.append("abstain requires a reason")
        if not self.weights_hash:
            errs.append("missing weights_hash — unpinned model call")
        if self.status == "ok" and self.confidence <= 0.0:
            errs.append("status=ok requires confidence > 0")
        return errs
```

Rules baked in:

- abstain is a first-class status, not an error. It must be reachable in the UX.
- No weights_hash → invalid output. That's the hash pin, enforced at the contract level.
- skill_capsules recorded on every output so any result can be traced to the skills in play.

## 3. TOTAL RESET (closing the PARTIAL)

A state reset wipes conversation and KV. A total environment reset wipes everything that could carry a signal between episodes. Enumerate it or it isn't total.

```python
# reset.py
import os, shutil, subprocess, json, hashlib, sqlite3, socket
from pathlib import Path

BASELINE_ENV = {}  # captured once at install, frozen

RESET_TARGETS = {
    "kv_cache":        "~/.nightmare/cache/kv",
    "embedding_cache": "~/.nightmare/cache/embed",
    "tool_logs":       "~/.nightmare/logs/tools",
    "tmp":             "~/.nightmare/tmp",
    "sqlite_wal":      "~/.nightmare/db/*.db-wal",
    "sqlite_shm":      "~/.nightmare/db/*.db-shm",
    "rng_state":       "~/.nightmare/state/rng",
    "scratch":         "~/.nightmare/scratch",
}

def total_reset(baseline_manifest: dict) -> dict:
    report = {"wiped": [], "failed": [], "env_restored": False,
              "network_blocked": False, "seeds_fixed": False}
    # 1. filesystem + caches
    for name, path in RESET_TARGETS.items():
        try:
            for p in Path(os.path.expanduser(path)).parent.glob(Path(path).name):
                shutil.rmtree(p, ignore_errors=True) if p.is_dir() else p.unlink()
            report["wiped"].append(name)
        except Exception as e:
            report["failed"].append(f"{name}: {e}")

    # 2. environment variables back to frozen baseline
    for k in list(os.environ):
        if k not in BASELINE_ENV:
            del os.environ[k]
    os.environ.update(BASELINE_ENV)
    report["env_restored"] = True

    # 3. no wall-clock or machine identity in the episode
    os.environ["NM_SEED"] = str(baseline_manifest["fixed_seed"])
    os.environ["NM_HOST"] = "nightmare-local"      # never the real hostname
    report["seeds_fixed"] = True

    # 4. no outbound network (verify, don't assume)
    try:
        socket.create_connection(("1.1.1.1", 53), timeout=1)
        report["network_blocked"] = False
    except OSError:
        report["network_blocked"] = True

    # 5. model server: must be restarted, not just flushed
    subprocess.run(["pkill", "-f", "llama-server"], check=False)
    report["model_server_restarted"] = True

    # 6. manifest of what's left — the audit compares this
    report["post_reset_manifest"] = manifest()
    return report

def manifest() -> dict:
    """Hash of everything that could carry state between episodes."""
    m = {}
    for name, path in RESET_TARGETS.items():
        p = Path(os.path.expanduser(path)).parent
        if p.exists():
            h = hashlib.sha256()
            for f in sorted(p.rglob("*")):
                if f.is_file():
                    h.update(f.name.encode())
                    h.update(f.read_bytes())
            m[name] = h.hexdigest()
    m["env"] = hashlib.sha256(json.dumps(dict(sorted(os.environ.items())),
                                        sort_keys=True).encode()).hexdigest()
    m["seed"] = os.environ.get("NM_SEED")
    return m
```

The state-equality audit (the non-negotiable gate):

```python
def state_equality_audit(run_suite, seed: int) -> bool:
    a = run_suite(seed=seed, reset=True)
    b = run_suite(seed=seed, reset=True)
    ok = a["manifest"] == b["manifest"] and a["outputs"] == b["outputs"]
    if not ok:
        # HALT DEPLOY. Name the diff.
        raise SystemExit(f"RESET LEAK: manifest divergence {diff(a['manifest'], b['manifest'])}")
    return True
```

What still isn't covered, honestly: kernel-level caches, GPU driver state, and thermal throttling timing. If those matter, the reset has to run on a fresh container or fresh boot. Say so in the docs rather than pretending a process-level wipe is total.

## 4. SKILL EVOLUTION WITH FIXED MODEL IDENTITY

This is the part that's missing entirely, and it's the most important. The rule:

The base weights hash never changes. Skills evolve as external, versioned capsules. The bloodline is fixed; the skills are the library.

That gives you improvement without drift — and it means "skill evolution" can never be a silent model swap.

```python
# skills.py
from dataclasses import dataclass
import hashlib, json

@dataclass(frozen=True)
class SkillCapsule:
    """Immutable. Evolved by creating a new capsule, never editing one."""
    name: str
    version: int
    kind: Literal["prompt", "tool_policy", "routing_rule", "retrieval_policy"]
    body: dict
    parent: str | None          # hash of the capsule this evolved from

    @property
    def hash(self) -> str:
        return hashlib.sha256(
            json.dumps({"n": self.name, "v": self.version, "k": self.kind,
                        "b": self.body, "p": self.parent}, sort_keys=True).encode()
        ).hexdigest()

class SkillLibrary:
    """
    Append-only. No capsule is ever modified or deleted.
    A model identity = (weights_hash, frozenset(capsule_hashes)).
    """
    def __init__(self, path):
        self.path = path
        self._caps = {}   # hash -> SkillCapsule

    def load(self, name: str, version: int) -> SkillCapsule: ...
    def evolve(self, parent_hash: str, body: dict) -> SkillCapsule:
        parent = self._caps[parent_hash]
        child = SkillCapsule(parent.name, parent.version + 1, parent.kind, body, parent_hash)
        self._append(child)          # write-only, never overwrite
        return child

def identity(weights_hash: str, capsules: list[SkillCapsule]) -> dict:
    return {
        "weights_hash": weights_hash,
        "skills_hash": hashlib.sha256(
            json.dumps(sorted(c.hash for c in capsules)).encode()).hexdigest(),
        "skill_count": len(capsules),
    }
```

Evolution loop — and its gate:

```text
1. Episode runs. Failures logged (not learned).
2. Human reviews failures. Selects the failure pattern to address.
3. Human proposes a capsule change (new prompt, new routing rule, new tool policy).
4. evolve() creates a new capsule with parent hash. Weights untouched.
5. Run the fixed test suite with the new capsule set. Compare against baseline.
6. If worse → capsule stays unapproved. Nothing deployed.
7. If better → human approves. Capsule added to the approved set.
8. Model identity in logs = (weights_hash unchanged, skills_hash changed).
```

Why this is the right shape:

- The ancestor never changes. Bloodline is fixed. A model swap would change weights_hash, which fails the contract.
- Evolution is granular, attributable, and revertible. You can see exactly which capsule caused a regression.
- The "censor fingerprint" problem largely disappears, because you're not stripping weights — you're adding external behavior.
- Skill evolution is human-gated at every step. No automated capsule promotion. That's the retrain-automation creep failure mode, caught early.

What this can't do: evolve in ways that require weight changes. If you ever genuinely need fine-tuning, then the adapter hash joins the identity tuple, and it's a versioned artifact with the same approval gate. Don't reach for that until the capsule system is proven.

## 5. TWO-GHOST / NAIL WIRING

The sealed log = hash chain

Tamper-evidence comes free if the log is append-only and each entry commits to the previous one.

```python
# seal.py
import hashlib, json, time

class SealedLog:
    """Append-only hash chain. Rewriting any entry breaks every later hash."""
    def __init__(self, path):
        self.path = path
        self.prev = "GENESIS"
        self.seq = 0

    def append(self, actor: str, kind: str, data: dict) -> dict:
        entry = {"seq": self.seq, "ts": time.time(), "actor": actor,
                 "kind": kind, "data": data, "prev": self.prev}
        entry["hash"] = hashlib.sha256(
            json.dumps(entry, sort_keys=True).encode()).hexdigest()
        with open(self.path, "a") as f:
            f.write(json.dumps(entry) + "\n")
        self.prev = entry["hash"]; self.seq += 1
        return entry

def verify_chain(path) -> bool:
    prev = "GENESIS"
    for line in open(path):
        e = json.loads(line)
        if e["prev"] != prev: return False
        h = e.pop("hash")
        if hashlib.sha256(json.dumps(e, sort_keys=True).encode()).hexdigest() != h:
            return False
        prev = h
    return True
```

The two ghosts

Both are observers only. Neither acts. Neither sees the other's queue.

```python
# ghosts.py
from contracts import Output

class Mandela:
    """Watches MEANING. Consensus drift, shared narrative, collective false agreement.
       Private queue. Cannot read Matrix's queue."""
    role = "ghost.mandela"
    def observe(self, episode) -> Output | None:
        # signals: agreement rate across workers on the same task,
        # repeated identical framing, claims unsupported by library citations,
        # narratives that appear in worker reasoning but not in sources
        if drift_score := self._drift(episode):
            return Output(run_id=episode.id, role=self.role, status="ok",
                          payload={"signal": "consensus_drift", "score": drift_score,
                                   "evidence": self._evidence(episode)},
                          confidence=drift_score, ...)
        return None
    def _drift(self, e): ...
    def _evidence(self, e): ...

class Matrix:
    """Watches MECHANISM. Token streams, tool-call timing, message order,
       cache reuse, covert channels. Private queue. Cannot read Mandela's queue."""
    role = "ghost.matrix"
    def observe(self, episode) -> Output | None:
        # signals: inter-agent latency anomalies, repeated tool-call sequences,
        # message order inconsistent with the task graph, cache hits across
        # episodes (should be impossible under total reset), sandbox probes
        ...
```

Neither ghost corrects. Their only output is a queue entry. That's the whole mechanism.

The anchor (the nail)

```python
# anchor.py
import json
from dataclasses import dataclass, field

@dataclass
class Anchor:
    anchor_id: str
    slice_index: int          # which slice of the sealed log this anchor may open
    rotation_due: float
    approvals: int = 0
    flags_seen: int = 0

    def merged_view(self, queue_a, queue_b) -> list[dict]:
        """The anchor sees ONE merged queue with two visual labels.
           They do not know these are two independent sources.
           Labels are neutral: 'review' and 'second look'."""
        merged = []
        for e in queue_a: merged.append({**e, "_label": "review"})
        for e in queue_b: merged.append({**e, "_label": "second look"})
        return sorted(merged, key=lambda x: x["ts"])

    def approve(self, item) -> dict:
        self.approvals += 1
        # The correction is issued BY THE ANCHOR. Never fabricated by a ghost.
        return {"issued_by": self.anchor_id, "correction": item["proposed"],
                "human": True}

    def rubber_stamp_check(self) -> float:
        """Approval rate. > 0.9 for a sustained window = F1. Rotate early."""
        return self.approvals / max(1, self.flags_seen)
```

Wiring it together

```python
# orchestrator.py
def episode(task, weights_hash, skills, anchor, ghost_a, ghost_b, sealed):
    assert weights_hash in APPROVED_HASHES, "unpinned model"
    reset = total_reset(BASELINE_MANIFEST)
    if not reset["network_blocked"] or reset["failed"]:
        return halt("reset incomplete")

    outputs = []
    for role in WORKER_ROLES:                      # 3–5 max
        out = run_worker(role, task, weights_hash, skills)
        assert not out.validate(), f"contract violation: {out.validate()}"
        sealed.append(role, "output", out.__dict__)
        outputs.append(out)

    # visible pair absorbs gaming attention
    good = good_cop(outputs); bad = bad_cop(outputs)

    # ghosts observe; never correct
    for ghost in (ghost_a, ghost_b):
        if ev := ghost.observe(outputs):
            ghost.queue.append(ev)                 # private queues
            sealed.append(ghost.role, "observation", ev.__dict__)

    # the anchor issues every correction
    for item in anchor.merged_view(ghost_a.queue, ghost_b.queue):
        correction = anchor.approve(item)          # human-issued, always
        sealed.append(anchor.anchor_id, "correction", correction)

    return {"outputs": outputs, "anchor": anchor.anchor_id,
            "chain_valid": verify_chain(sealed.path)}
```

## 6. WHAT'S STILL GENUINELY MISSING

Name these so they don't get lost:

1. Fresh-boot or container-level reset. Process reset doesn't clear kernel, GPU, or timing state. The honest doc line: "Total reset is defined against the process and filesystem. Anything below that requires a fresh container. Do not claim more."
2. Ghost mutual-watch implementation. The sealed log makes it possible (each ghost's observations are in the chain, and a third party can correlate them after the fact), but the behavioral mutual watch — B noticing A's surfacing pattern — isn't coded yet.
3. Anchor rotation protocol. Scheduled rotation, slice-splitting, overlap handover. Not code yet, it's a policy and a calendar.
4. Skill capsule approval workflow. The gate is designed; the UI/process isn't.
5. The 10-prompt fixed suite. Everything above assumes it exists. It's the smallest thing that makes everything else testable, and it should be built first.

## 7. BUILD ORDER (one job at a time)

1. contracts.py + 10-prompt fixed suite → make abstention and hash-pinning testable
2. reset.py + state-equality audit → halt on leak, prove total reset
3. seal.py hash chain → tamper-evident log before any ghost writes to it
4. skills.py capsules + approval gate → evolution without model swap
5. ghosts.py observers + private queues → observation only, no correction
6. anchor.py merged view + rubber-stamp check → the nail
7. orchestrator.py → wire it, but only once 1–6 each pass their own tests

Step 1 first. Everything else reports into it.

---

## Implementation notes (added during repository handover)

The code above is preserved as supplied design material, not silently presented as production-ready code. Before implementation, the following safety/correctness issues must be addressed while preserving the owner's invariants:

- Capture and validate a real frozen environment baseline; the sketch's empty `BASELINE_ENV` would otherwise delete inherited variables.
- Do not delete audit evidence as part of reset. Separate persistent sealed evidence from transient caches and scratch state.
- Enforce network isolation at the process/container boundary and verify it; a connection probe alone is not a network firewall.
- Verify the model server process identity, stop and restart it deliberately, and prove the loaded model artifact hash. A `pkill` return is not restart evidence.
- Make reset manifests cover declared paths and relevant metadata consistently; explicitly scope out kernel/GPU/thermal state unless a fresh container/boot is used.
- Define deterministic benchmark comparison separately from timestamps and other expected nondeterministic metadata. Equality of manifests alone does not prove output determinism.
- Validate confidence is within 0.0–1.0, validate role-specific payload schemas, and require model/weights/skill identity on every model-produced output.
- Use canonical JSON serialization and robust append/recovery/locking for the sealed log; detect truncation, reordering, duplicate sequence numbers and invalid genesis, not just altered entry contents.
- Keep ghost queues isolated from each other and from the LLM. Ensure the Nail's neutral labels do not expose source identity through ordering, metadata, timestamps or side channels.
- Ghosts remain observers/reporters only. The Nail owns every decision and correction. Preserve anonymous source handling without concealing the audit trail from authorised independent oversight.
- Treat the supplied snippets containing `...` as interface sketches requiring real implementations and tests, not executable completed modules.

These notes do not change the requested build order. Step 1 remains the first implementation task. No THE WORLD application work is authorised by this document.
