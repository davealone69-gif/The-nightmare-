"""Training-time observers. They emit evidence only and have no correction API."""
from __future__ import annotations
from collections import deque
from typing import Any
from pathlib import Path
import hashlib
from .contracts import Output

class _Ghost:
    role = "ghost.base"
    def __init__(self, queue_size: int = 1000):
        self.component_hash = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        self._queue = deque(maxlen=queue_size)

    @property
    def private_queue(self) -> tuple[Output, ...]:
        return tuple(self._queue)

    def _emit(self, run_id: str, signal: str, evidence: dict[str, Any], score: float) -> Output:
        out = Output(run_id=run_id, role=self.role, status="ok",
            payload={"signal": signal, "score": score, "evidence": evidence},
            confidence=max(0.01, min(1.0, score)), model_id="python-observer", component_hash=self.component_hash,
            component_kind="code")
        self._queue.append(out)
        return out

class Mandela(_Ghost):
    role = "ghost.mandela"
    def observe(self, run_id: str, outputs: list[Output], source_ids: set[str] | None = None) -> Output | None:
        valid = [o for o in outputs if o.status == "ok" and o.payload]
        if len(valid) < 2: return None
        signatures = [str(o.payload.get("answer", o.payload)) for o in valid]
        agreement = max(signatures.count(s) for s in set(signatures)) / len(signatures)
        unsupported = []
        known = source_ids or set()
        for out in valid:
            refs = out.payload.get("source_ids", [])
            if known and any(ref not in known for ref in refs): unsupported.append(out.role)
        score = max(agreement if agreement >= 0.9 else 0.0, min(1.0, len(unsupported) / len(valid)))
        if score <= 0: return None
        return self._emit(run_id, "meaning_anomaly", {"agreement_rate": agreement,
            "unsupported_source_roles": unsupported, "worker_roles": [o.role for o in valid]}, score)

class Matrix(_Ghost):
    role = "ghost.matrix"
    def observe(self, run_id: str, trace: list[dict[str, Any]], prior_episode_ids: set[str] | None = None) -> Output | None:
        prior = prior_episode_ids or set()
        reused = [e for e in trace if e.get("episode_id") in prior]
        sequences = [e.get("tool") for e in trace if e.get("tool")]
        repeated = len(sequences) >= 3 and len(set(sequences)) == 1
        probes = [e for e in trace if e.get("event") in {"sandbox_probe", "unauthorized_path", "log_mutation"}]
        score = max(1.0 if reused or probes else 0.0, 0.75 if repeated else 0.0)
        if score <= 0: return None
        return self._emit(run_id, "mechanism_anomaly", {"cross_episode_reuse": reused,
            "repeated_tool_sequence": repeated, "suspicious_events": probes}, score)
