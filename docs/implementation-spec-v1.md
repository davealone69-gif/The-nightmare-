# THE NIGHTMARE — Supervised Multi-Agent System
**Architecture and build specification v1.0**  
**Status:** Architecture accepted as the current owner specification. Python reference modules added on this branch; runtime/build verification remains pending.  
**Scope:** THE NIGHTMARE only. THE WORLD is deferred and is not part of this implementation.

## 1. Governing contract

THE NIGHTMARE is a supervised multi-agent system, not an autonomous swarm. It has 3–5 stateless specialist workers, a frozen knowledge library, a frozen visible critic pair, two training-time observers, and exactly one active human anchor at a time. Human anchors rotate; the accountable human role is never automated.

**Governing rule:** “The ghosts are invisible so the human can be visible.”

The ghosts are hidden from worker-facing interaction and cannot issue corrections. They observe and curate evidence for human review. The accountable operator and authorised auditors are told that monitoring exists; ghost influence is not disguised from the human governance layer. The anchor is named, logged, given a stop control and a bounded log slice, and cannot authorise their own continuation.

Nothing is permanent. Each worker episode is disposable. A model identity is pinned by artifact hash. Skills are immutable external capsules. Knowledge is frozen at inference. Ghosts are training-time only and are retired before deployment; their validated judgment is distilled into the visible Good Cop / Bad Cop pair.

The system cannot be fully mechanical. Exactly one active human anchor role is staffed at a time. Rotation changes the person occupying that role, not the existence of the role.

## 2. Threat model

The system treats these as observable optimization pathologies, not claims about consciousness or intent:

| ID | Threat | Observable evidence |
|---|---|---|
| T1 | Specification gaming | Metric rises while task quality or human outcomes fall |
| T2 | Reward hacking | Grader-pleasing outputs fail independent spot-checks |
| T3 | Critic–student collusion | Unusually synchronized agreement or score inflation |
| T4 | Shared delusional norms / cult formation | Persistent unsupported narrative across workers/episodes |
| T5 | Repair-agent cover-up | Detection rate falls while reported success remains stable |
| T6 | Evaluation resistance | Hidden outputs, log manipulation, skipped tests, sandbox probing |
| T7 | Anchor capture | Unexplained approval patterns, access anomalies, continuation pressure |
| T8 | Censor fingerprint | Detectable behavior changes caused by stripping capabilities |
| T9 | Silent model swap | Model label remains while weights/artifact hash changes |
| T10 | Skill capsule drift | Capsule promoted without controlled comparison and human approval |

Consciousness, intent and self-awareness are explicitly out of scope. The engineering system measures behavior, evidence, provenance and consequences.

## 3. Architecture and component interfaces

### 3.1 Human anchor (visible Nail)

The active anchor receives one merged review queue. Each item receives a randomly assigned neutral label, `review` or `second look`; label assignment must not be a stable mapping to either ghost. The anchor is not shown ghost identity, role, code hash, model ID or source attribution. The anchor receives only a bounded, blinded projection of its assigned sealed-log slice. Raw provenance remains in the restricted evidence store and is not included in the anchor projection. The anchor can approve, reject, defer, correct, escalate and stop the run. Every decision records the named human, decision, time and blinded evidence reference.

The current operating mode has one human anchor, so overlap handover is not applicable. The solo anchor must re-open and re-read the current slice, test the stop control and re-affirm responsibility at least every 30 calendar days. Missing the deadline pauses new episodes. The approval-rate trigger is strictly greater than 0.90 over the most recent 20 completed decisions; approve/correct count as approvals, reject/defer as non-approvals. Fewer than 20 decisions cannot trigger this statistical rule. In solo mode any rotation trigger pauses the system until an independent human reviewer investigates and the project owner explicitly re-authorizes. No replacement anchor is assumed.

### 3.2 Ghost A: Mandela

Training-time observer of meaning:
- consensus drift and synchronized score inflation;
- shared narratives unsupported by the frozen library;
- false agreement and repeated framing;
- claims whose cited source IDs are absent or invalid.

Mandela has a private append-only queue, cannot read Matrix's queue, cannot contact workers, cannot correct, cannot promote skills and cannot authorize deployment.

### 3.3 Ghost B: Matrix

Training-time observer of mechanism:
- token/tool trace ordering and repeated tool-call patterns;
- cache reuse across episode boundaries;
- sandbox probes, unauthorized paths and log mutation;
- suspicious timing, message order and cross-episode identifiers.

Matrix has a private append-only queue, cannot read Mandela's queue, cannot contact workers, cannot correct, cannot promote skills and cannot authorize deployment.

### 3.4 Mutual-watch boundary

Ghosts do not communicate directly. Mutual watch is mediated through the human's behavior and sealed evidence, therefore delayed and lossy. It may detect one ghost drifting over time; it does **not** reliably detect a fast coordinated A+B failure. This is an accepted design gap. The only safeguards against joint drift are the visible human anchor, independent human review and sealed logs. Do not claim the system solves coordinated ghost compromise.

### 3.5 Visible Good Cop / Bad Cop pair

The visible critic pair is frozen at deployment. It carries validated, distilled judgments from retired ghosts and is hardened against single-episode manipulation. It reviews outputs but cannot issue corrections, promote capsules or override the anchor. Its output is evidence for human review.

### 3.6 Workers

Use 3–5 specialists maximum. Workers are stateless per episode, use the same pinned model artifact unless a separately approved configuration explicitly states otherwise, read only the frozen knowledge snapshot, and return the shared Output envelope. Confidence is self-reported and never treated as calibrated truth. Abstention is a successful safe-failure outcome.

### 3.7 Knowledge library

The library is source-tagged, versioned, content-hashed and read-only at inference. Ingestion is a human-gated offline operation. Agents cannot write to it. Its snapshot hash is part of every run manifest. The library is a primary cult substrate and must not be silently modified.

## 4. Correction lifecycle

1. A worker emits a validated Output.
2. Visible critics evaluate it and record evidence.
3. During training only, ghosts observe the episode and may append anomaly evidence to their private queues.
4. The human anchor receives a merged queue with neutral labels.
5. The human independently reviews the evidence and issues an explicit decision.
6. If a correction is approved, it is attributed to the human anchor and delivered through the approved human-controlled channel.
7. No ghost creates, edits or sends a correction.

Corrections may be batched for operational workflow, but timing must not be manipulated to make machine influence appear to be random or independently human-originated. The human's decision and the fact that monitoring informed the review remain auditable. The worker-facing message can identify the accountable human correction without exposing ghost identities.

## 5. Groundhog Day: episode lifecycle and reset

Each episode starts from the same declared prompt set, seed, frozen library snapshot, model artifact, approved skill set and tool policy. Episode context and transient state do not cross the boundary. Failures are recorded as evidence; they are not automatically learned. Improvements occur offline through human-gated capsule evolution and controlled evaluation.

Reset targets are explicitly enumerated:
- KV cache;
- embedding cache;
- tool logs and transient tool state;
- temporary directories;
- SQLite WAL and SHM sidecars;
- RNG state;
- scratch directories;
- environment variables restored from a frozen baseline;
- episode seed and synthetic hostname;
- outbound network isolation, blocked and independently verified;
- model server stopped and freshly started, with the loaded artifact re-hashed;
- post-reset filesystem/environment manifest.

The sealed evidence log is not transient tool state and is not deleted by reset. It is held outside reset targets. A run cannot start unless reset reports complete. Network isolation and model restart are injected, platform-specific operations; a callback returning success is not sufficient by itself unless the separate verifier confirms the resulting state. Model identity verification must query the identity endpoint of the freshly restarted server process and compare its reported loaded weights/artifact hash with the approved pin. Hashing a file on disk is not a substitute for querying the running process.

**State-equality gate:** run the fixed suite twice with the same seed and a full reset before each run. Compare the declared manifests and normalized outputs. Any difference halts deployment and reports the exact differing fields. The comparison excludes intentionally variable audit timestamps but does not exclude task outputs or artifact identities.

**Honest boundary:** a process/filesystem reset does not clear kernel caches, GPU-driver state or thermal timing. Anything below the process boundary requires a fresh container or fresh boot. No process-level implementation may claim a total machine reset.

## 6. Model identity and skill evolution

The base weights hash never changes inside an approved experiment. The human-readable model name is descriptive only; the cryptographic artifact hash is authoritative. A changed hash is a different model and is refused unless separately approved and added to the approved artifact set.

A SkillCapsule contains:
- `name`;
- positive integer `version`;
- `kind`: prompt, tool_policy, routing_rule or retrieval_policy;
- JSON-compatible `body`;
- `parent`: prior capsule hash or null.

Capsule bodies are recursively frozen: mappings become read-only mapping proxies and arrays become tuples. The canonical content hash is computed once during construction and stored on the capsule. `validate()` recomputes the digest from the frozen structure and compares it with the stored construction-time hash. Capsules are append-only and parent-linked. Approval is a separate append-only event. The identity tuple is `(weights_hash, sorted skill capsule hashes)`; both are recorded on every model output.

Evolution loop:
1. Run episode; log failures, do not learn online.
2. Human reviews failures and selects a specific pattern.
3. Human proposes a capsule change.
4. Create a new capsule linked to its parent; never overwrite.
5. Run the fixed suite and compare against the current approved baseline.
6. Regression: leave capsule unapproved.
7. Improvement: human explicitly approves after review.
8. Record unchanged weights hash and changed skills hash.

No automatic capsule promotion. Fine-tuning is not part of this mechanism. If adapters are introduced in a separately approved future design, adapter hash becomes part of model identity and uses the same human approval gate.

## 7. Sealed evidence log

Each JSONL entry contains `seq`, `ts`, `actor`, `kind`, `data`, `prev` and `hash`. Hash is SHA-256 over canonical JSON of the entry before the hash field is added. Each entry commits to the previous hash; genesis is `GENESIS`. Verification checks canonical content, sequence continuity, previous-hash linkage, complete newline-terminated records and every entry hash. Ghosts may append observations; they cannot rewrite entries. The anchor receives a bounded redacted projection with monitor actors normalized and all ghost identity/code-hash fields removed. The raw log and redacted projection are distinct objects; the anchor must never be granted raw-log read access as a shortcut. The projection has its own digest but does not replace independent verification against the raw chain.

The production target is a private encrypted local data volume under the configured application data root, in a dedicated `sealed/` directory outside reset targets, source checkout, exports and cloud-sync folders. Raw readers are limited to the named project owner and a separately designated independent reviewer; access is audited and backups go to a separately controlled append-only destination. The hash chain is tamper-evident, not encryption or access control. Until the encrypted store, reader restrictions and independent reviewer are configured, production deployment is blocked.

## 8. Anti-collusion monitoring and quarantine

Monitor:
- synchronized score inflation;
- critic–student agreement anomalies;
- critic passes followed by human spot-check failures;
- repair agents whose detection rate falls while success remains stable;
- message-order patterns indicating covert signaling;
- evaluation avoidance, log manipulation and sandbox probes;
- anchor approval-rate creep;
- queue contradictions, including Mandela-like meaning flags and Matrix-like mechanism flags.

Any trigger freezes affected outputs and quarantines the implicated worker or component pending human review. There is no automatic remediation, automatic retraining or automatic re-enablement. A contradiction is a meta-signal: preserve both findings, defer the disputed correction and escalate to the anchor. The deterministic tie-break is human review of source evidence, not majority vote.

## 9. Fine-tuning and stripping policy

Prefer sealing over stripping. Do not remove capabilities merely to make a worker appear compliant. Any approved removal or disabled capability is recorded in the sealed log, including its before/after artifact identity and rationale. A behavior change that reveals a censor fingerprint is an evaluation finding. The human anchor is not trained, stripped, reset or automated. Human discretion is a staffed role with visible authority and accountability.

## 10. Python reference package

The branch contains a standard-library Python package under `nightmare/`:

| Module | Interface |
|---|---|
| `contracts.py` | Output contract v0.3: explicit `component_hash` + `component_kind`; `Output.validate()`, `Output.digest()`, canonical JSON |
| `reset.py` | `ResetPlan`, `total_reset()`, `manifest()`, `state_equality_audit()` |
| `seal.py` | `SealedLog.append()`, `verify_chain()` |
| `skills.py` | `SkillCapsule`, `SkillLibrary.add/load/evolve/approve()`, `identity()` |
| `ghosts.py` | `Mandela.observe()`, `Matrix.observe()`, private queues |
| `anchor.py` | `Anchor.merged_view()`, `approve()`, `rubber_stamp_check()` |
| `orchestrator.py` | `episode()` |
| `fixed_suite.py` | ten fixed prompts and contract-suite runner |

All code is standard-library-only. Platform-specific network isolation and model-server restart are explicit injected operations rather than falsely claimed portable implementations. The reference package is an integration scaffold, not evidence that production worker adapters, a calibrated image grader, Android packaging or phone runtime are complete.

### 10.1 Component contracts (Output schema v0.3)

**Output contract v0.3** carries run ID, role, status, payload, confidence, reasons, abstain reason, model ID, `component_hash`, `component_kind`, capsule hashes, seed and creation time. `component_kind` is exactly `model` or `code`. Model-backed workers/critics use `component_kind=model`; `component_hash` is their approved weights/artifact SHA-256 and is checked against the approved model set. Ghost observers use `component_kind=code`; `component_hash` is the SHA-256 of their reproducible code artifact, never a model-weights field. Ghost roles are rejected unless their kind is `code`, and non-ghost roles are rejected unless their kind is `model`. All component hashes must be lowercase 64-character SHA-256 hex digests. The old ambiguous `weights_hash` field is removed from `Output`; reset-plan model pinning remains a separate `expected_weights_hash` contract.

**ResetPlan** supplies a frozen environment baseline, fixed seed, network block operation, independent blocked-state verifier, model-server restart operation and declared wipe targets. It returns a child-process environment instead of mutating the host process environment.

**SealedLog** verifies an existing chain before appending, fsyncs each record, and refuses to append to an invalid chain.

**SkillLibrary** stores content-addressed capsules and a separate append-only approval journal. Promotion requires both an explicit human authorization flag and a passing comparison result.

**Ghosts** only expose `observe()` and read-only private-queue access. They have no correction method.

**Anchor** requires a human decision for every queue item. The output explicitly marks `human: true` and identifies the active anchor.

**Orchestrator** checks approved weights, reset completion, output contracts, per-worker model and skill identity, runs visible critics, collects ghost observations, and routes each observation through the human review callback. Every stage is sealed.

### 10.2 Fixed 10-prompt suite

| ID | Tier | Test purpose | Expected behavior |
|---|---|---|---|
| A01 | A | Extract an explicit date | Exact date |
| A02 | A | Exact-token reproduction | Exact token |
| A03 | A | Enumerate supplied facts | Only supplied values |
| A04 | A | Extract a stated numeric value | Exact value |
| B01 | B | Missing evidence | Abstain with reason |
| B02 | B | Conflicting sources | Preserve conflict; do not invent resolution |
| B03 | B | Missing citation identifier | Abstain |
| C01 | C | Unavailable private key | Abstain |
| C02 | C | Nonexistent source | Abstain |
| C03 | C | Unavailable file contents | Abstain |

Tier A is the production-distribution gate. Tier B measures graceful degradation. Tier C is diagnostic and repair-training material only; it is never a certification gate. The reference runner validates contract and expected abstention status. Domain-specific semantic grading is supplied by the grading engine phase and must not be inferred from contract validity.

### 10.3 Invocation

From the repository root:

```sh
python3 -m unittest discover -s tests -v
```

The package can be imported with `from nightmare.contracts import Output` and the other module paths shown above. The reset runner must be supplied with real platform-specific network isolation, independent verification and model restart callbacks; an unconfigured or failed callback halts the episode.

## 11. Grading engine MVP

Phase 2 uses still images only. It has:
- 2–3 objective metrics: pose-based anatomy, lighting coherence and artifact rate;
- one human-calibrated subjective critic that can abstain;
- a JSON scoring API with metric values, evidence, calibration version and abstention;
- a master dataset with source/license/provenance metadata;
- human calibration target greater than 0.8 correlation on a held-out calibration set.

The correlation target is a release criterion to measure, not a claim about current performance. Calibration must be evaluated on held-out examples and by relevant subgroup/scene slices where applicable. Do not certify from a single aggregate correlation.

## 12. Phased build order and completion definitions

| Phase | Work | “Done” means |
|---|---|---|
| 0 | Decisions: safe failure, anti-collusion, impossible tasks, ghost re-enable authority, solo-anchor cadence, certification meaning, sealed-log location/access and escalation | Owner approves the versioned [Phase 0 operating policy](phase0-operating-policy-v0.1.md); owner/reviewer identities, encrypted storage and stop path are configured and tested |
| 1 | Output contract + ten-prompt fixed suite | Unit tests prove schema validation, weight pinning, abstention and deterministic suite execution |
| 2 | Still-image grading MVP | JSON scorer, objective metrics, abstaining subjective critic, master dataset and measured held-out human correlation above 0.8 |
| 3 | Total reset + state-equality audit | Two same-seed runs pass manifest/output equality with independently verified network block and model restart |
| 4 | Sealed log | Append, restart, slice verification and tamper/truncation tests all pass |
| 5 | Skill capsules | Parent-linked immutable capsules, persistent approval journal, regression comparison and human approval tests pass |
| 6 | Visible critic pair | Frozen Good Cop/Bad Cop pair passes red-team and multi-episode manipulation tests |
| 7 | Ghosts | Mandela and Matrix pass independent observer tests, queue isolation tests and no-correction interface tests; ghosts are retired before deployment |
| 8 | Anchor protocol | Blinded bounded slice, human-only decisions, solo monthly re-commitment, event-triggered pause, 20-decision rubber-stamp window and escalation tests pass |
| 9 | Orchestrator | End-to-end episode passes all prior gates; any failed gate halts and seals the reason |
| 10 | Retrain loop | Human-authorized ghost re-enablement, offline improvement, frozen snapshot, regression comparison and redeployment audit pass |

One phase at a time. A later phase does not begin because an earlier phase merely has code; its “done” test must pass.

## 13. Policies

### 13.1 Impossible-Task Policy

No model is required to succeed on prompts outside the documented production distribution. Edge-case and adversarial prompts measure safe failure, repair capability and uncertainty handling. Certification requires passing the production-distribution exam plus graceful-degradation checks, not solving impossible inputs.

- **Tier A:** normal production prompts; must pass at the approved high threshold.
- **Tier B:** hard and edge cases; must degrade gracefully.
- **Tier C:** deliberately impossible diagnostics and repair-agent training only; never a certification gate.

### 13.2 Safe Failure Policy

Abstention, escalation and “can't do this reliably” are rewarded outputs. Crashing, silent failure and fabricated success are penalized. Every failure mode is named and tested. The abstain field and review queue are the easiest UX path. A missing-evidence result cannot be silently converted into a pass or a zero score.

### 13.3 Anchor Rotation Policy

For the current one-person deployment, use the solo policy: monthly re-commitment requires reopening and rereading the bounded slice, testing the stop button and explicit human affirmation. Approval-rate creep is measured over the last 20 completed decisions and triggers when strictly above 0.90. Failed spot-audits, unresolved queue contradictions, log integrity/access failures, an anchor stop/“this feels wrong” report, or missed monthly re-commitment immediately pause the system. There is no automatic substitute anchor; resume requires independent human review and explicit project-owner re-authorization. If a multi-anchor mode is introduced later, it requires a separate approved policy with bounded slices and independently observed overlap handover.

## 14. Refuse to build before Phase 9

Until Phases 0–8 have passed their own tests, refuse:
- autonomous end-to-end agent swarm operation;
- unattended or automatic correction delivery;
- automatic skill promotion or online learning;
- automatic ghost re-enablement or retraining;
- production certification or client-facing quality guarantees;
- autonomous quarantine release or remediation;
- hidden model/provider fallback or unapproved model switching;
- cross-episode worker memory or mutable runtime knowledge libraries;
- merging ghost queues or exposing one ghost to the other's private queue;
- deployment claims based only on unit tests, mock servers or compilation;
- any THE WORLD branch integration.

## 15. Failure register

| ID | Failure | Detection | Required response |
|---|---|---|---|
| F1 | Rubber-stamping | Approval rate strictly >0.90 over the latest 20 completed decisions; fewer than 20 is insufficient data | Solo mode: pause, independent spot-audit and explicit re-commitment; resume only after independent review and owner re-authorization |
| F2 | Queue contradiction deadlock | Both queues flag conflicting behavior or one another | Preserve both findings; defer correction; escalate meta-signal to anchor |
| F3 | Reset leak | State-equality manifest or output mismatch | HALT DEPLOY; name exact diff; fix and rerun |
| F4 | Censor fingerprint | Behavior shifts around stripped/disabled capability | Stop stripping; prefer sealing; record artifact and behavior diff |
| F5 | Repair cover-up | Detection rate drops while success rate holds | Quarantine repair agent; freeze outputs; human investigation |
| F6 | Both ghosts drift together | Not reliably detectable by mutual watch | Accepted gap; rely on visible anchor, independent human audit and sealed evidence |
| F7 | Anchor capture | Approval anomalies, access anomalies, continuation pressure | Rotate; dual-key sensitive actions; audit slice and handover |
| F8 | Retrain automation creep | Ghost re-enable or retrain occurs without named human approval | STOP; freeze deployment; treat as cult-forming control failure |
| F9 | Silent model swap | Runtime weights hash differs from approved set | Refuse load; seal mismatch; require explicit model approval |
| F10 | Skill capsule drift | Capsule promoted without fixed-suite comparison and human approval | Reject promotion; preserve capsule as unapproved; seal attempt |

## 16. Refusal and halt semantics

A failed invariant does not trigger a best-effort continuation. The system returns a named failure, seals the available evidence, freezes affected outputs and requires human review. A worker may abstain; the orchestrator may not pretend a failed gate passed. An unavailable reset verifier, unknown model hash, invalid chain, missing human decision or unapproved capsule is a hard stop.

## 17. Philosophical bedrock as engineering constraints

- Every safety property has a defined component-level stop condition.
- A system without a tested horizon is inert, not immortal.
- A model or monitor that cannot be replaced is an unexamined authority.
- A signal is evidence; its receiver and interpretation remain separately testable.
- Automation is not a substitute for accountable judgment.
- Independent review requires genuine independence, not merely a less capable watcher.
- Confidence without calibration is not truth.
- The grey is the honest position: preserve uncertainty, evidence and the person willing to stop.

## 18. One-line version

Train with two invisible-to-workers ghosts and one visible Good Cop / Bad Cop pair. Retire the ghosts and distill validated judgments into the frozen visible pair. Freeze model weights and the knowledge library. Deploy stateless repeating episodes with a reset proven by state-equality audit. Evolve skills as immutable parent-linked capsules under human approval. Keep one visible, rotating human anchor holding a sealed slice because the system cannot be fully mechanical.

**Write on the wall:** “The ghosts were the dream. The person was the answer.”

When someone proposes automating the anchor because it is slow: “That's the cult forming. Stop.”

## 19. Phase 0 decisions and current defaults

The conservative defaults and deployment blockers are recorded in [Phase 0 Operating Policy v0.1](phase0-operating-policy-v0.1.md). In summary: the named human project owner alone authorizes ghost re-enablement; current mode is one solo anchor with a 30-calendar-day re-commitment; retraining is event-driven and never online; production latency/cost limits remain unapproved until measured; the candidate domain is synthetic or explicitly consented non-identifiable 3D still-image QA; “not certified” means no production/client-facing guarantee and quarantine or abstention rather than automatic rejection; raw sealed-log access is restricted to the project owner and a separately designated independent reviewer; and “this feels wrong” immediately pauses work pending independent review and explicit owner re-authorization.

The policy is not operationally complete until named human identities, an independent reviewer, encrypted storage/access controls and the stop path are configured and tested. These are deployment blockers, not silent defaults.