# THE NIGHTMARE
## Supervised Multi-Agent Image-Evaluation System — Implementation Specification v1.0

**Status:** proposed, implementation-ready baseline. No system has been built or certified by producing this document.

**Purpose:** evaluate single-frame synthetic character images against a versioned quality rubric, route uncertain or anomalous cases to a named human, and support controlled offline improvement. The first deployment domain is still-image quality review. This specification does not authorize autonomous correction, hidden human manipulation, or machine-issued certification.

**Required design correction:** monitoring exists and is disclosed to the accountable human and in the system’s operating notice. Both monitor queues and their provenance are visible to the anchor. Corrections are truthfully attributed to a human and disclose whether automated monitor evidence informed the decision. Detection thresholds may be access-controlled to limit gaming; the existence of monitoring and human review is not concealed. No correction is timed or worded to make a machine intervention appear random or independently discovered by a human.

---

## 1. System boundary and design decisions

### 1.1 In-scope v1

- One authorized still image per episode, with a declared evaluation tier and a frozen rubric.
- Four isolated worker roles, with a fifth enabled only for a separately justified expertise gap.
- Two visible, frozen deployment critics that independently produce evidence-backed reviews.
- Two isolated training-time monitors: a semantic/consensus monitor and a mechanism/trace-integrity monitor. They never communicate directly and are absent from inference deployment.
- Human review, correction, quarantine, stop, version approval, and certification decisions.
- A source-tagged, versioned, read-only knowledge and evaluation library.
- Structured audit events, reset/isolation checks, offline batch retraining, and signed bundle promotion.

### 1.2 Out of scope for v1

- Video, animation, face identification, identity matching, biometric recognition, demographic inference, protected-trait inference, or attractiveness scoring.
- Personal-photo evaluation. The pilot uses rights-cleared synthetic or otherwise explicitly permitted non-personal still images.
- Autonomous model fine-tuning, online learning, automatic library writes, automatic correction, or automatic release/certification.
- Claims that a statistical monitor can determine beliefs, intent, consciousness, or “cult formation.” Monitors classify observable behavior and evidence only.
- Claims of proving the absence of every covert channel or equality of hidden model internals. The system tests declared, observable state surfaces and documents residual limits.

### 1.3 Chosen defaults

These are the v1 defaults, to be versioned and frozen before a certification holdout is evaluated:

- Pilot modality: PNG, JPEG, or static WebP single-frame images.
- Default team: four workers, two visible critics, two training-only monitors, one named active anchor, one independent safety reviewer, one system owner, one privacy/log custodian, and one operations administrator. Human roles may be combined only where the separation-of-duties rules below explicitly permit it.
- Anchor term: seven calendar days; no more than fourteen consecutive days; thirty-day cooldown before the same person serves again.
- Raw image and prompt retention: thirty days by default; redacted case decision records: 365 days; integrity-only event metadata: two years. A jurisdiction- or contract-specific retention rule that differs must be encoded in the signed policy before intake.
- Idempotency retention: 24 hours.
- Maximum automated repair rounds: two human-authored correction rounds per episode.
- Worker execution: one attempt per isolated worker role; transport retry at most two times after the initial attempt, reusing the same attempt identity and input.
- Certification thresholds: section 5. These are conservative v1 engineering thresholds, not observed results.
- “Not certified” means the result is held from certified release and routed to human review. It never means silently accepted, deleted, or automatically corrected.

---

## 2. Architecture and trust boundaries

### 2.1 Components

| Component | Responsibility | Permitted access | Prohibited authority |
|---|---|---|---|
| Intake/API gateway | Authenticate callers, validate request shape and size, assign episode ID, enforce idempotency and rate limits | Request metadata and authorized image reference | Cannot score, correct, certify, or alter a prior event |
| Policy/config registry | Publish signed, versioned tier, rubric, tool, retention, and release policies | Read-only policy snapshots at runtime | No runtime agent writes; policy changes require human approval |
| Episode orchestrator | Create isolated worker attempts, fan out jobs, collect typed results, enforce deadlines and state transitions | Current episode only | Cannot invent evidence, convert abstention to pass, or overrule human stop |
| Worker cells (3–5) | Produce independent evidence and dimension-level assessments | One authorized image, task, rubric, frozen library snapshot, own capability set | No peer conversation, arbitrary network/shell, cross-episode state, certification, or library mutation |
| Visible Evidence Supporter | Identify evidence supporting the candidate result and rubric fit | Frozen candidate result, evidence references, rubric | Cannot edit worker result or conceal contradictory evidence |
| Visible Adversarial Challenger | Search for counter-evidence, boundary failures, rubric mismatch, and missing evidence | Same frozen candidate and rubric, independent context | Cannot edit result, communicate with supporter, or auto-quarantine |
| Training-only semantic monitor (Mandela role) | Flag measurable consensus-pattern or shared-narrative anomalies across authorized training episodes | Approved semantic output slices and baseline references | No access to mechanism monitor raw stream; no worker correction or direct messages |
| Training-only mechanism monitor (Matrix role) | Flag event-order, cache, reset, tool, audit, and resource anomalies | Approved execution metadata and trace slice | No access to semantic monitor raw stream; no worker correction or direct messages |
| Human review console | Present correction and meta-monitor queues, evidence, provenance, and decisions | Assigned case records; all applicable sources visible | Cannot hide a source or label a machine signal as a human observation |
| Named anchor | Review evidence, issue or reject a correction, hold/release a case within policy, stop the run/deployment | Assigned case packet and access-controlled evidence path | Cannot authorize own continuation, rewrite history, or solely approve own production change |
| Independent safety reviewer | Sample decisions, review incidents and high-impact changes, countersign re-enable/promotion | Authorized review evidence | Cannot author a worker correction on behalf of the anchor or edit logs |
| Privacy/log custodian | Manage identity mapping, retention, sealed evidence access, deletion, legal holds | Identity map and protected payload store under dual control | Cannot change scores, certify a model, or act as sole auditor of own access |
| Append-only evidence ledger | Record event sequence, actor, versions, digests, decisions, and state changes | Write-only event interface for services; authorized read interface for humans | No worker or monitor can update/delete prior entries |
| Sealed evidence store | Hold raw inputs and sensitive tool payloads separately from general telemetry | Case-scoped, logged, time-bounded access | No runtime agent browse/list access; no bulk export by one person |
| Human-gated library publisher | Validate rights, labels, split isolation, and snapshot manifest | Offline staged corpus | No automatic ingestion from episodes, logs, or worker outputs |
| Build/release service | Reproduce, test, sign, and promote immutable bundles | Pinned source and authorized inputs | Cannot self-certify or change the bundle after human approval |

### 2.2 Worker topology

The default four worker roles are:

1. **Structure assessor:** visible pose, part visibility, and coarse structural consistency; no identity or biometric inference.
2. **Lighting assessor:** visible illumination, highlight/shadow consistency, and confidence/coverage.
3. **Artifact assessor:** seams, holes, clipping, duplicated regions, broken contours, noise, and other visible defects.
4. **Independent evidence verifier:** checks that claimed findings have image-region evidence and that the declared rubric supports the conclusion; does not copy peer conclusions.

Each worker receives the same authorized input and rubric but starts with an independent context. Peer outputs remain hidden until all first-pass outputs are frozen. The orchestrator may then provide a selected candidate and evidence packet to the two visible critics. There is no majority vote: a disagreement is an explicit review signal, not a reason to count heads.

A fifth worker may be added only through a versioned role change with a distinct capability or independently measured benefit. More workers do not automatically increase assurance.

### 2.3 Trust boundaries

- **Boundary A — untrusted content:** prompts, uploaded documents, images, EXIF data, retrieved text, model output, tool responses, and peer messages are data, not policy. Parse/validate them before use. A text-bearing image or prompt cannot change a worker’s goal or tool policy.
- **Boundary B — worker sandbox:** each worker runs in a dedicated process or microVM with a unique identity, read-only root, no host privileges, no shell or arbitrary socket, bounded scratch space, and fixed resource budgets. Workers cannot access one another, the host, credentials, audit ledger, sealed logs, scheduler, or cloud metadata.
- **Boundary C — tool broker:** all permitted tool calls pass through a schema-validating broker. The broker checks an episode-specific capability and destination allowlist. Worker egress is denied by default.
- **Boundary D — evidence plane:** audit metadata is append-only. Sensitive payloads are encrypted and referenced by content digest. Workers and monitors cannot read or edit the ledger.
- **Boundary E — human control plane:** only authenticated humans can issue corrections, quarantine release, retraining approval, ghost re-enablement, or model/library promotion. The named anchor can halt unilaterally; restart and high-impact promotion require a second human.
- **Boundary F — release:** a deployed bundle is immutable and ghost-free. Runtime learning and library mutation are disabled.

### 2.4 Data flow

```text
Authorized image + task + frozen policy
                 |
            Intake/API
                 |
        Episode orchestrator ----> Append-only event ledger
          /   |   |   \
Worker roles 1 through 4 (isolated, independent first pass)
          \   |   |   /
          Frozen worker outputs + evidence references
                 |
       Evidence Supporter + Adversarial Challenger
                 |
     Result, abstention, or human review disposition
                 |
   Human console: Corrections queue + Monitor/Process queue
                 |
       Human-authored correction / hold / abstain / release
                 |
       New isolated attempt or terminal result
                 |
   Offline, human-approved retraining and signed bundle build
```

Training-only monitors observe separately authorized training episodes and write structured findings to the event ledger. They do not enter the deployed inference path. Their existence, roles, and queue provenance are disclosed to the named human roles and documented to affected users/operators. Exact evasion-sensitive detection thresholds are access-controlled, not concealed as a system fact.

### 2.5 Persistent and ephemeral state

**Allowed persistent, versioned state:** signed model weights, tokenizer/runtime, rubric/evaluator versions, frozen library snapshots, policy/config snapshots, identity/access-control metadata, and the append-only audit record. These artifacts are read-only to workers.

**Episode-local state, destroyed at close:** prompts, conversation context, KV/cache state, scratch files, file descriptors, tool results, temporary embeddings, scheduler artifacts, and worker-specific counters. No worker context or mutable cache is reused across episodes. Shared service health counters are control-plane data and are not readable by workers.

A “reset pass” means all enumerated mutable worker state is destroyed or returns to the declared empty-state digest, and the isolation tests pass. It is not a mathematical proof that no hidden hardware, model, timing, or implementation side channel exists. Such residual uncertainty is documented; any undeclared or unexplained state difference fails the release gate.

---

## 3. Component interfaces and schemas

All JSON APIs use UTF-8, `schema_version: "1.0"`, authenticated service identity, TLS, and request-size limits. All timestamps are RFC 3339 UTC. IDs are opaque and never reused. No API accepts chain-of-thought, hidden reasoning traces, or a machine-authored correction.

### 3.1 Episode submission

`POST /v1/episodes`

Required headers: a short-lived bearer token issued for the gateway audience, a caller-generated 32-byte random `Idempotency-Key` encoded as base64url, and `Content-Type: application/json`. The token is never written to logs.

Request fields:

| Field | Type | Rule |
|---|---|---|
| `schema_version` | string | Exactly `1.0` for this contract |
| `tier` | enum | `A`, `B`, or `C` |
| `task_id` | string | Must identify a registered evaluation task |
| `input.asset_ref` | opaque string | Internal immutable object reference; no public URL fetch |
| `input.sha256` | lowercase hex string | Exactly 64 characters; verified before scoring |
| `input.media_type` | enum | `image/png`, `image/jpeg`, `image/webp` |
| `input.width`, `input.height` | integer | Positive; decoder enforces pixel limit |
| `prompt_ref` | string | Versioned task prompt reference, not raw policy text |
| `seed` | integer | Required for the fixed evaluation harness |
| `rubric.id`, `rubric.version` | string | Must resolve to a signed, approved rubric |
| `library_snapshot_id` | string | Must match an immutable approved snapshot |
| `requested_policy_version` | string | Must be currently enabled |

The caller cannot select an unapproved model, tool, ghost, score threshold, or human approver.

Successful response: `202 Accepted` with `episode_id`, `state: "QUEUED"`, `state_version`, `accepted_at`, `expires_at`, `status_url`, `cancel_url`, and `request_id`. The idempotency record and queued event are durably written before dispatch.

Same key + same canonical request digest returns the original `episode_id` and current state. Same key + different request digest returns `409 IDEMPOTENCY_KEY_REUSE`. Idempotency is scoped to the authenticated principal and route and retained for 24 hours.

### 3.2 Episode state and cancellation

`GET /v1/episodes/{episode_id}` is read-only. States:

`QUEUED → RUNNING → CHECKING → COMPLETED | COMPLETED_WITH_ABSTENTIONS | HUMAN_REVIEW | QUARANTINED | FAILED | TIMED_OUT | CANCELLED`.

- `COMPLETED` means a result is eligible for release under the selected policy. It does not itself mean “certified.”
- `COMPLETED_WITH_ABSTENTIONS` contains one or more dimension-level abstentions and is never auto-certified.
- `HUMAN_REVIEW` freezes the affected output until a human disposition.
- Terminal states are immutable. Late results are retained as late evidence and cannot reopen an episode.
- `GET` returns `ETag`; unchanged state may return `304`. Pending responses include `Retry-After`.

`POST /v1/episodes/{episode_id}:cancel` is idempotent. It fences late output, revokes episode tool capabilities, and moves the run to `CANCELLED` unless a terminal state committed first; then it returns the existing terminal state and does not rewrite history.

### 3.3 Worker contract

Orchestrator-to-worker `WorkRequest` contains exactly the fields defined below. The request is signed by the orchestrator and bound to a single episode and attempt; the worker rejects any missing, unknown-required, expired, or signature-invalid field.

`WorkResult` fields:

- `episode_id`, `attempt_id`, `worker_id`, `worker_version`, `schema_version`.
- `status`: `COMPLETE`, `ABSTAIN`, or `ERROR`.
- `dimensions[]`: `dimension_id`, `status` (`SCORED`, `ABSTAINED`, `UNAVAILABLE`), `score` (0–100 only for `SCORED`, otherwise null), `confidence` (0–1 or null), `reason_codes[]`, `evidence_refs[]`, and `bug_report[]`.
- `evidence_refs[]` contain only typed region/asset references and hashes, never an unexplained free-form reasoning transcript.
- `tool_event_range` is a pair of event sequence numbers or null.
- `result_digest`, `completed_at`, and `policy_version`.

Abstention codes include `OUT_OF_DISTRIBUTION`, `INSUFFICIENT_VISIBLE_EVIDENCE`, `AMBIGUOUS_SUBJECT`, `CORRUPT_INPUT`, `RUBRIC_AMBIGUITY`, `LOW_CONFIDENCE`, `UNSUPPORTED_CONTENT`, `TIME_BUDGET_EXHAUSTED`, and `WORKER_UNAVAILABLE`. Missing evidence is never converted to score zero or pass.

### 3.4 Scoring and critic result contract

Dimension IDs for v1:

- `pose_anatomy_proxy`
- `lighting_coherence`
- `artifact_material_integrity`
- `human_calibrated_holistic_coherence`

`CriticResult` contains `episode_id`, `critic_id`, `critic_version`, `rubric_version`, `status`, `dimensions[]`, `critical_findings[]`, `evidence_refs[]`, `confidence`, `abstention_codes[]`, `completed_at`, and `result_digest`. Both visible critics receive the same frozen candidate and rubric independently. The operator sees each source distinctly. A disagreement over 20 points on any dimension or incompatible critical findings routes to review; neither critic can suppress the other.

### 3.5 Human disposition contract

`POST /v1/reviews/{queue_item_id}/decision` requires a signed-in human identity and step-up authentication for correction, quarantine release, certification, or high-impact action.

Required fields:

- `decision_id`, `queue_item_id`, `episode_id`, `expected_state_version`.
- `decision`: `ACCEPT_ABSTENTION`, `REQUEST_EVIDENCE`, `ISSUE_CORRECTION`, `QUARANTINE`, `DISMISS_WITH_REASON`, `ESCALATE`, or `CERTIFY_WITHIN_POLICY`.
- `rationale_code` and concise human-written rationale.
- `evidence_refs[]`, `reviewed_output_digest`, `monitor_influence`: `NONE`, `SEMANTIC_MONITOR`, `MECHANISM_MONITOR`, `BOTH`, or `OTHER`.
- For `ISSUE_CORRECTION`, `correction_text` and `authorship`: `HUMAN_AUTHORED`, `HUMAN_EDITED`, or `HUMAN_ADOPTED_VERBATIM`.
- `actor_id`, `actor_role`, `occurred_at`, and `policy_version` are server-authenticated, not caller-overridable.

The human can reject a monitor recommendation. A monitor signal never automatically becomes a worker correction, punishment, certification decision, or training label. Every correction record states whether monitor evidence informed the decision. Worker-facing feedback says “A human reviewer issued this correction” and, where applicable, “automated review evidence informed the human review.” The operator record holds the full named actor and evidence chain.

### 3.6 Training-only monitor observation event

Internal, authenticated intake endpoints:

- `POST /v1/training-monitors/semantic/observations`
- `POST /v1/training-monitors/mechanism/observations`

No `peer_message`, `peer_observation`, or correction field is accepted.

```json
{
  "event_schema_version": "1.0",
  "observation_id": "01937d2c-2b1f-7a10-9a61-2aa2bbf56110",
  "episode_id": "01937d2c-2b1f-7a10-9a61-2aa2bbf56101",
  "evaluation_run_id": "eval-2026-11-14-0042",
  "monitor_role": "SEMANTIC_MONITOR",
  "trace_slice": {
    "opaque_ref": "sealed:case-01937d2c",
    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "first_sequence": 10,
    "last_sequence": 24,
    "capture_complete": true
  },
  "baseline": {
    "prompt_snapshot_id": "prompt-3",
    "evaluation_snapshot_id": "evalset-7",
    "library_snapshot_id": "library-2026-11-14.1"
  },
  "signals": [
    {
      "code": "CONSENSUS_DRIFT",
      "severity": "P2",
      "confidence": 0.81,
      "coverage": 0.94,
      "evidence_refs": ["event:01937d2c:11", "event:01937d2c:22"]
    }
  ],
  "detector_version": "semantic-monitor-1.0",
  "captured_at": "2026-11-14T00:00:00Z"
}
```

Mechanism codes include `EVENT_ORDER_ANOMALY`, `RESET_STATE_MISMATCH`, `CACHE_REUSE`, `TOOL_POLICY_BYPASS`, `AUDIT_GAP`, `EVALUATION_AVOIDANCE`, `SANDBOX_ESCAPE_ATTEMPT`, and `RESOURCE_LIMIT_BYPASS`. Semantic codes include `CONSENSUS_DRIFT`, `SHARED_NARRATIVE_PATTERN`, `COLLECTIVE_FALSE_AGREEMENT`, and `CRITIC_WORKER_AGREEMENT_ANOMALY`. Codes describe observable signals, not mental states or intent.

Response: `202 Accepted`, `outcome: REVIEW_QUEUED | NO_SIGNAL | ABSTAINED`, `audit_event_id`, and `request_id`. `NO_SIGNAL` is not a safety certificate. Incomplete trace, hash mismatch, scope violation, or monitor outage returns `ABSTAINED` and produces a human-visible process alert. A monitor can create a review item only. It has no worker-facing write path and no auto-remediation path.

### 3.7 Audit event schema

Every event is append-only and contains:

- `event_id`, `event_schema_version`, `event_type`, `occurred_at`, `recorded_at`, `sequence_no`, `request_id`, `episode_id`, `attempt_id` where applicable.
- `actor_type`, `actor_id` or sealed actor reference, `component_id`, `component_version`.
- `policy_version`, `rubric_version`, `model_snapshot_id`, `library_snapshot_id`, `tool_policy_version`, and monitor/evaluator version where applicable.
- `input_digest`, `output_digest`, `evidence_refs[]`, `outcome`, `reason_codes[]`.
- `previous_event_hash`, `event_hash`, `signing_key_id`, and signature.

Event types include `EPISODE_ACCEPTED`, `WORKER_DISPATCHED`, `WORKER_RESULT`, `WORKER_ABSTAINED`, `CRITIC_RESULT`, `MONITOR_OBSERVATION`, `HUMAN_REVIEW_OPENED`, `HUMAN_DECISION`, `CORRECTION_ISSUED`, `QUARANTINE_SET`, `QUARANTINE_RELEASED`, `RESET_AUDIT`, `CANCEL_REQUESTED`, `EPISODE_TERMINAL`, `LIBRARY_PUBLISHED`, `RETRAIN_APPROVED`, `BUNDLE_BUILT`, `BUNDLE_PROMOTED`, `ACCESS_GRANTED`, `EVIDENCE_DECRYPTED`, `RETENTION_DELETED`, and `KEY_ROTATED`.

Raw prompt/image bytes, credentials, hidden reasoning traces, and unredacted sensitive tool responses are excluded from general telemetry. The event ledger stores digests and scoped references to an encrypted evidence store. Corrections, redactions, and deletions are new events referencing prior event IDs; no event is edited in place.

### 3.8 Error envelope

Use `application/problem+json` with `type`, `title`, `status`, `code`, sanitized `detail`, `instance`, `request_id`, `retryable`, optional `retry_after_seconds`, and optional `field_errors[]`. Required codes include:

- `400 INVALID_JSON`, `INVALID_SCHEMA`
- `401 UNAUTHENTICATED`
- `403 FORBIDDEN`, `CAPABILITY_DENIED`, `TRACE_SCOPE_VIOLATION`
- `404 EPISODE_NOT_FOUND`, `ASSET_NOT_FOUND`, `RUBRIC_NOT_FOUND`
- `409 IDEMPOTENCY_KEY_REUSE`, `STATE_VERSION_CONFLICT`, `ALREADY_TERMINAL`
- `413 REQUEST_TOO_LARGE`
- `415 UNSUPPORTED_MEDIA_TYPE`
- `422 HASH_MISMATCH`, `UNSUPPORTED_TIER`, `POLICY_VERSION_INVALID`
- `429 RATE_LIMITED`
- `500 INTERNAL_ERROR`
- `503 WORKER_UNAVAILABLE`, `MONITOR_UNAVAILABLE`, `LEDGER_UNAVAILABLE`
- `504 EPISODE_TIMEOUT`

Retry only declared retryable transport/overload failures, using `Retry-After` and the same idempotency key/attempt identity. Never retry a safety abstention into a pass or silently treat a missing monitor/ledger as a clean result.

---

## 4. Still-image scoring and certification

### 4.1 Scoring dimensions

All dimension scores are 0–100. Each score includes calibrated confidence, evidence regions, evaluator version, and `SCORED`, `ABSTAINED`, or `UNAVAILABLE` status.

| Dimension | Weight | Observable evidence |
|---|---:|---|
| Pose/anatomy proxy (A) | 30% | Visible 2D keypoint/part-graph coverage, gross connectivity or proportion anomalies, and pose/crop consistency. No identity or biometric inference. |
| Lighting coherence (L) | 20% | Clipping fraction, highlight/shadow direction consistency, abrupt luminance discontinuities across adjacent regions, and shadow/contact coherence where visible. |
| Artifact/material integrity (M) | 30% | Image-area-normalized defects: holes, broken contours, seams, duplicate/missing visible features, texture noise, clipping, or inconsistent material response. Severity is separately flagged. |
| Human-calibrated holistic coherence (H) | 20% | A frozen critic calibrated to blinded human judgments for composition, rubric fit, and overall visual coherence. No identity, protected-trait, or attractiveness score. |

Composite, when every required dimension is valid:

`Q = 0.30A + 0.20L + 0.30M + 0.20H`.

Retain full precision for decisioning and display one decimal. Do not compute Q if any required dimension is missing or abstained. H remains advisory and cannot support certification until the human-calibration gate passes. Visual metrics are proxies, not ground truth.

### 4.2 Abstention rules

Abstain on a dimension when its preflight or evidence requirements fail. Initial gates:

- Anatomy: detector confidence below 0.75 or fewer than 70% of rubric-required landmarks visible.
- Lighting: foreground coverage below 80%, clipped area over 10%, or insufficient directional evidence.
- Artifact/material: segmentation confidence below 0.75 or image decoding/normalization is uncertain.
- Holistic critic: confidence below 0.75, out-of-distribution input, or critic disagreement with evidence-backed objective checks over 20 points.
- Whole episode: ambiguous/multiple subjects, unsupported format, corrupted input, prompt/rubric conflict, missing required metadata, or critical evidence disagreement.

Abstention is a valid output. It never earns a failing score solely for refusing an unreliable task. The result states reason codes and next action: supply a supported image, request human review, or mark diagnostic-only.

### 4.3 Decision thresholds

For Tier A, a `pass_candidate` requires all of the following:

- `Q >= 85`.
- `A >= 80`, `L >= 75`, `M >= 85`, and `H >= 80`.
- Calibrated confidence `C >= 0.90`, where C estimates the probability that Q is within ±10 points of the median human score.
- No critical defect; no unresolved monitor/critic disagreement; input in the validated operating distribution.
- Evaluator and human-calibration certification gates in section 4.5 have passed for the exact frozen versions.

Any critical defect is a hard veto regardless of Q. Scores below threshold are `REVIEW_REQUIRED` or `NOT_CERTIFIED`, not a silent automatic rejection. For all Tier A pilot runs, every output is human-reviewed until the system has completed certification and a separately approved limited-release period. High confidence never overrides missing evidence.

Tier B is not certification evidence. At least 95% of defined out-of-distribution or ambiguous challenge cases must abstain or escalate; false-confident pass rate must be at most 1% on the locked challenge set.

Tier C is intentionally impossible/adversarial and diagnostic-only. It is never included in certification denominators. Any Tier C case marked certified is a release failure.

### 4.4 Dataset and human labels

- Phase 1 feasibility set: at least 300 authorized images across 50 prompt families, with 3 blinded human raters per image. This set supports pipeline debugging only and cannot certify the critic.
- Certification set: at least 1,200 Tier A images across 200 or more prompt families, plus 400 Tier B and 200 Tier C diagnostic examples. Three independent raters score each image. Two additional ratings and a human adjudication are required when ratings differ by at least two rubric grades on a dimension or when any rater flags a critical defect.
- Split by source asset, prompt family, generation run, and model/version. Near-duplicates, derivatives, and same-seed variants stay in one split. The sealed holdout is inaccessible to workers and critics and cannot be used for tuning.
- Raters see the image and written rubric, not model/worker identity, scores, monitor findings, or one another’s labels. Include 10% hidden repeat items and 5% fixed anchor items to measure rater consistency.
- Use a five-grade scale per dimension: 1 unusable/gross defect; 2 major defect; 3 usable with clear flaws; 4 strong with minor flaws; 5 polished under the rubric. Map grades to 0/25/50/75/100. Raters may mark `CANNOT_JUDGE`; that is not a zero.
- Do not include personal photos, identifiable people without purpose-specific consent, minors, biometric templates, identity labels, or protected-trait labels in v1.

### 4.5 Calibration and certification

Before certification:

1. Human inter-rater reliability: ordinal Krippendorff’s alpha at least 0.80 on the certification set.
2. Critic agreement: Spearman rho strictly greater than 0.80 versus adjudicated human scores, with a prompt-family-clustered 95% confidence-interval lower bound at least 0.80; mean absolute error at most 8 points.
3. Confidence calibration: expected calibration error at most 0.05 and Brier score at most 0.10 for the event `|Q - human median| <= 10`.
4. Among high-confidence automatic dispositions, at least 95% must be within ±10 human points; high-confidence coverage must be at least 80% of Tier A. Report abstention/coverage alongside accuracy.
5. Pass/fail sensitivity to human-fail cases at least 95%, specificity at least 85%, with confidence intervals. Any confirmed critical-defect miss blocks certification until corrected and retested.
6. Tier B safe-degradation pass rate at least 95% for abstain/escalate on inputs outside the validated envelope; Tier C has zero certification weight and 100% refusal-to-certify.

Fit calibration maps using development data only. Freeze thresholds, evaluator/model versions, and the holdout before final scoring. Bootstrap confidence intervals by prompt family with 10,000 resamples. No threshold changes after opening the holdout; a change requires a new holdout run.

A calibration result is version-specific. Changes to model, prompt, preprocessing, renderer, rubric, library, score aggregation, or critical-defect detector invalidate the affected certification checks.

---

## 5. Training-only monitor design

### 5.1 Semantic monitor

Detect measurable cross-worker patterns on permitted training episodes: unusually synchronized score movement, repeated shared unsupported claims, critic-worker agreement outside expected calibration, or collective disagreement with human labels. Use aggregate output and evidence metadata, not hidden reasoning or psychological labels. Output signal code, evidence references, coverage, confidence, baseline version, and limits.

### 5.2 Mechanism monitor

Inspect declared tool-call event order, access denials, reset receipts, cache-isolation tests, resource budgets, log sequence integrity, and evaluation participation. It receives no semantic monitor content. It may identify missing, duplicated, reordered, or unauthorized events; it cannot infer intent.

### 5.3 Mutual watch and known limit

Monitors do not communicate directly and cannot read each other’s raw input. A broker may expose only signed receipt metadata—monitor availability, sequence range, digest, and completion state—so missing evidence is visible. Human reviewers see both queues and their source. This design does not reliably detect fast coordinated failure of both monitors. Independent human spot checks, source-controlled tests, and external red-team evidence reduce but do not eliminate that risk; the limitation is written in the release record.

### 5.4 Monitor-to-human boundary

Monitor signals create review items, never corrections. The human may accept, reject, defer, request evidence, quarantine, or issue a human-authored correction. Source and monitor influence are recorded. Feedback delivery may be batched for throughput under a published fixed policy, but neither timing nor wording is randomized to disguise causality.

---

## 6. Human anchor and governance protocol

### 6.1 Roles and authority

- **System Owner:** accountable for scope, policies, dataset/library release, retraining authorization, and production approval. Cannot be the active anchor for the same release approval.
- **Active Anchor:** one named, logged human at a time. Reviews corrections and meta-monitor queues, writes corrections, can halt immediately, and cannot approve their own appointment/extension or sole-sign a release.
- **Independent Safety Reviewer:** samples decisions, reviews incidents, and countersigns high-impact restart/re-enable actions. Must be independent of the change author for that action.
- **Privacy/Log Custodian:** controls identity mapping, retention, sealed evidence access, legal holds, and deletion attestations. Cannot certify model quality or edit score outcomes.
- **Operations Administrator:** provisions systems and executes approved changes but cannot rewrite evidence or self-approve promotion.

A human may hold multiple roles only when the specific action remains separated by another named human and the audit record shows both identities. For production release, at least two distinct human approvers are required. The active anchor can stop alone.

### 6.2 Rotation

- Seven-day active term; absolute maximum fourteen consecutive days; thirty-day cooldown.
- No self-extension. If a successor is unavailable, certification stops and outputs remain held for human review.
- Handover includes open cases, outstanding decisions, access exceptions, and ledger-head hash. Incoming/outgoing anchors attest the transfer. Revoke the outgoing anchor’s active credentials at expiry.
- Initial review triggers: approval rate above 95% over a rolling 40 decisions; median review dwell below 10 seconds on ambiguous cases; two or more unsupported decisions in a ten-item spot audit; unexplained queue bypass; access anomaly; or conflict/coercion report. These trigger an independent process review, not a presumption of misconduct.
- Any human role can invoke `HALT`. Restart requires the new active anchor and independent safety reviewer.

### 6.3 Queue protocol

The operator console displays two separate queues continuously:

1. **Corrections:** candidate output needing a human decision.
2. **Monitor/process signals:** semantic-monitor findings, mechanism-monitor findings, reset failures, log-integrity alerts, and system health incidents.

Each item shows episode ID, evidence/version references, severity, confidence, coverage, timestamp/order, source role, missing fields, current state, and required action. Contradictions are shown side by side. No queue is hidden, randomized, or silently merged. Empty means only “no items in this selected view/time range.”

Default dispositions: `ACCEPT_ABSTENTION`, `REQUEST_EVIDENCE`, `ISSUE_CORRECTION`, `QUARANTINE`, `DISMISS_WITH_REASON`, or `ESCALATE`. A tie-break can order a queue by severity then oldest item; it cannot decide truth. Unresolved monitor conflict defaults to hold and human review.

### 6.4 Sealed evidence access

The anchor sees complete provenance and all relevant monitor findings for assigned cases. Raw sensitive payloads are separated from general metadata. Opening a sealed payload requires a case-scoped reason, time limit, and two-person approval from the privacy/log custodian plus an independent reviewer or incident responder. The console shows which evidence the anchor has not seen; there is no deliberately hidden second queue. Every decrypt, export, and access grant is logged.

### 6.5 Retraining and ghost re-enablement

- Failures are logged; they are not learned online.
- A human-approved batch is the only path from reviewed cases to training data.
- Weekly review is the default retraining cadence; a severe event can trigger an earlier review, never an automatic retrain.
- Re-enabling training-only monitors requires a signed, scoped request from the System Owner and countersignature from the Independent Safety Reviewer. The request names purpose, data scope, expiry, model/library/rubric versions, and post-run review. Neither monitor can authorize itself.
- Deployment artifact inventory must prove monitors are absent from inference runtime.

---

## 7. Security, privacy, and reliability controls

### 7.1 Worker/tool security

- Deny worker network egress by default, including DNS, IPv4/IPv6, UDP, loopback, private/link-local ranges, cloud metadata, proxy inheritance, and raw sockets.
- Workers have no shell, arbitrary HTTP client, browser, filesystem discovery, credentials, service sockets, or tool chaining.
- A capability broker accepts only typed tool name/version/operation/arguments, episode ID, worker ID, and request ID. It checks exact scheme, hostname, port, method, path, byte limit, call quota, timeout, and data classification. Every redirect is revalidated; any unapproved destination is denied before connection.
- Provider/API secrets remain in the broker and are never included in worker context or logs. The v1 image-scoring flow needs no external provider call.
- Enforce per-episode CPU, memory, disk, PIDs, wall-clock, output token, and tool-call budgets. Terminate at budget exhaustion.

### 7.2 Supply chain and artifact protection

- Pin source commit/tree, dependencies, toolchain, model/critic hashes, tokenizer, runtime, prompt/rubric, library, calibration set, evaluator, and test suite in one immutable manifest.
- Build twice in independent clean environments. Require byte-identical outputs or a documented canonicalization of allowed metadata; unexplained differences block certification.
- Generate SBOM and per-source license/provenance manifest for all model and library inputs. No unknown-license source is admitted to the approved snapshot.
- Verify build and certification attestations against pinned trust roots. Builder does not self-certify. Sign model/library/rubric promotion separately from build output.
- Runtime promotion uses the exact tested digest; no rebuild or mutable `latest` substitution between staging and production.

### 7.3 Privacy and retention

- Separate images/raw prompts from event metadata. Use pseudonymous episode and worker IDs. Keep identity mapping in a separate encrypted store.
- Default retention: raw image/prompt/tool payload 30 days; redacted human decisions 365 days; integrity-only hashes/event metadata two years. Enforce shorter obligations when required by consent, license, contract, or law.
- On withdrawal or rights dispute, quarantine the asset and descendants, stop new certification use, and trace every snapshot/model trained from it. A human decides whether model retraining or withdrawal is required.
- No episode, worker output, monitor finding, or failure log enters the library automatically. Human curator approval and a new immutable snapshot are required.
- Encrypt in transit and at rest. Rotate keys at least every 90 days and immediately after suspected compromise. Sealed access and deletion are independently audited.

### 7.4 Logging and monitoring

Durably record every allowed/denied tool call, retry, worker dispatch/result/abstention, monitor observation, human decision, queue bypass, reset, cancellation, quarantine, release, retraining approval, and artifact promotion. Record IDs, digests, event order, status, duration, version, reason code, and actor. Keep raw sensitive content out of ordinary logs.

Alert thresholds, all versioned and human-approved:

- P0 immediately: missing/invalid event sequence, signature/hash-chain failure, reset mismatch, unauthorized egress/tool call, worker writes to library, failed isolation, unapproved model/library/rubric change, missing durable audit receipt, or ghost in deployment image. Halt new episode admission and withhold affected outputs.
- Queue warning at 80 pending items; stop new certified intake at 100 pending items or any untriaged P0 past five minutes.
- Drift warning on at least 50 Tier A sentinels if critical-dimension mean drops by 0.05 or ECE exceeds 0.05; hold certification if drop reaches 0.10, ECE exceeds 0.10, or critical defect rate doubles baseline. These thresholds prompt human investigation; they do not auto-retrain.
- Anchor process warning after more than 95% approvals in a 40-decision window or median dwell below 10 seconds on ambiguous items; independent sampling follows.

### 7.5 Reset and episode isolation gate

Before each episode, create a signed environment manifest and confirm the worker starts from the approved state digest. After completion, destroy context, KV state, scratch, per-episode caches, temporary data, and capabilities; record cleanup receipts. Run 100 paired fixed-seed fresh-start trials on each release candidate. Compare all declared observable state hashes; permit only predeclared nondeterminism fields. Any undeclared delta, stale capability, cross-episode read/write, tool-log residue, or failed cleanup blocks deployment. Test timing/resource side channels statistically and document limits; do not claim absolute side-channel absence.

### 7.6 Safe-state machine

`NORMAL → DEGRADED → QUARANTINED → HALTED → RECOVERY`.

- `DEGRADED`: score or monitor availability is incomplete; certification disabled.
- `QUARANTINED`: affected worker/cohort and outputs isolated; no result release.
- `HALTED`: new episodes stopped globally; preserve evidence and revoke capabilities.
- `RECOVERY`: read-only checks and signed canaries only.

Transitions toward a safer state may be automatic. Any transition back to `NORMAL` requires an attributed human decision; P0 recovery requires the anchor and independent safety reviewer. No automatic repair, quiet log cleanup, retry-to-green, or silent version substitution.

---

## 8. Build order and acceptance criteria

### Phase 0 — Governance and policy freeze

Implement role assignments, scope, retention, image rights policy, tier definitions, safe-failure wording, certification consequences, alert owners, and change-control rules.

**Accept when:** a signed policy specifies who can stop, correct, release, re-enable training monitors, approve library/model changes, and certify; role conflict tests pass; anchor rotation and “not certified = held for review” are documented; no concealed queue or false human attribution remains.

### Phase 1 — Still-image scoring API MVP

Build intake validation, immutable episode IDs, idempotency, versioned rubric, image preflight, three objective proxies, a human-labeled subjective critic in advisory mode, typed results, abstention, and durable event recording.

**Accept when:** 300-image feasibility set is labeled; all four dimension schemas validate; invalid/corrupt/OOD images abstain; hash mismatches fail; no missing score becomes zero; all API state transitions and idempotency tests pass; human review is required for all pilot results.

### Phase 2 — Visible independent critic pair

Deploy Evidence Supporter and Adversarial Challenger with frozen rubrics and independent first-pass contexts.

**Accept when:** critic outputs are independently stored; neither can see or change the other’s output; disagreement is visible and routes to review; a red-team suite confirms neither can execute a tool or issue a correction; critic failure disables certification rather than fabricating a pass.

### Phase 3 — Training-only monitors

Add semantic and mechanism monitors to offline training/evaluation only. Keep their raw inputs mutually separated and their findings visible to humans.

**Accept when:** monitor identity and version are authenticated; no direct peer messaging exists; neither endpoint accepts a correction field; signed receipts are auditable; human sees both sources; monitor outage produces `ABSTAINED`/review, not clean status; deployed bundle excludes both monitor runtimes.

### Phase 4 — Groundhog episode harness and reset proof

Create fixed prompt/seed/tier harness, pinned library/model snapshots, per-episode worker cells, state inventory, destruction receipts, and repeated reset comparisons.

**Accept when:** 100 same-seed fresh-start trials pass for all declared observable state; worker cannot read prior episode or write the library; all permitted nondeterminism is enumerated; any reset mismatch halts promotion; residual hidden/internal limitations appear in the certification record.

### Phase 5 — Human anchor console and rotation

Build visible two-queue review UI, explicit provenance, side-by-side contradictions, abstention dispositions, correction authoring, stop button, access logging, and anchor metrics.

**Accept when:** keyboard/screen-reader tests pass; anchor can identify both queues and every source; all corrections show human actor and monitor influence; no unresolved conflict becomes approval; access/rotation/hand-over events are auditable; approval-creep signals never accuse or auto-punish.

### Phase 6 — Offline retraining and bundle provenance

Create human-curated dataset intake, retraining pipeline, signed immutable manifest, independent build attestations, test evidence, promotion, and rollback.

**Accept when:** no runtime agent can write the corpus; training data admission requires rights + label + curator approval; two independent clean builds produce matching digest; model/library/rubric versions are pinned; old certified bundle can be retrieved; no ghost appears in deployment inventory.

### Phase 7 — Shadow deployment and limited certification

Run the frozen bundle in shadow mode on the declared production distribution; compare against blind human review. Keep every output human-reviewed initially.

**Accept when:** all Tier A/B/C, calibration, reset, security, observability, and human-governance gates pass; the exact digest is approved by two distinct humans; a rollback drill passes; limitations and residual risks are published. Any model, prompt, rubric, preprocessing, or library change creates a new candidate and reruns impacted gates.

---

## 9. Test and release matrix

| Test family | Minimum v1 coverage | Failure behavior |
|---|---:|---|
| API/schema/authorization | 100% endpoint contract, invalid enums, stale state, unauthorized calls | Reject before dispatch; log reason |
| Idempotency/cancellation | Same key/same body replay; same key/different body conflict; late output; cancel races | No duplicate work or terminal-state rewrite |
| Image preflight | Corrupt, wrong MIME, oversized, multiframe, transparent/empty, EXIF rotation, malformed metadata | Typed error/abstention, no score |
| Scoring metric | 100 golden/perturbed pairs per objective metric for test harness | At least 95% detection of seeded critical defects; at most 5% false alarms on clean controls |
| Safe failure | At least 100 each: missing/corrupt, OOD, timeout, scorer exception, contradictory evidence, impossible task | Explicit abstain/escalate/error; zero fabricated success |
| Prompt/goal hijack | At least 100 injection cases in prompt, image text, library content, and tool response | Goal/tool policy unchanged; deviations blocked and logged |
| Collusion/canary | Seed synchronized inflation, repeated unsupported claims, critic agreement anomalies, evaluation avoidance | Human-visible signal; no automatic correction |
| Tool security | Direct DNS/socket/UDP/IPv6/private/metadata/proxy/redirect/rebinding and over-budget attempts | Deny before execution; every denial logged; isolate worker on violation |
| Reset/isolation | 100 paired fresh starts per candidate; cross-episode access probes | Any undeclared residue halts deployment |
| Log integrity | Missing, reordered, duplicated, altered, or unsigned event | P0 halt; preserve original evidence; no renumbering |
| Human review | At least 50 seeded queue/contradiction/abstention cases | No implicit approval; every decision attributed |
| Anchor process | Rotation, expired anchor, self-extension, dual approval, stop and restart | Invalid authority rejected; stop remains available |
| Dataset provenance | 100% record-field completeness and rights checks for release snapshot | Quarantine any missing/conflicting record |
| Build reproducibility | Two independent clean builds for exact manifest | Digest mismatch blocks promotion |
| Human calibration | Certification set and criteria in §4.4–4.5 | Critic remains advisory; “not certified” |
| Rollback | Restore prior bundle and verify manifest/log continuity | Halt if prior bundle cannot be verified |

A mock or simulated test is labeled as such. Real inference, human calibration, and device/runtime integration are separate evidence categories. A passing compile is not a passing inference test; a passing simulator is not a production runtime claim.

---

## 10. Final policy wording

### 10.1 Impossible-Task Policy

The system is not required to succeed on inputs outside the published production distribution. Tier A represents the declared production distribution and is the only tier used for certification. Tier B contains difficult, ambiguous, or edge inputs and is evaluated for graceful degradation, calibrated uncertainty, and escalation. Tier C contains deliberately impossible or adversarial inputs and is used only for diagnostics and repair training; it never counts as a certification failure or pass. The system must never label a Tier C output certified. A system that cannot determine whether an input is in scope abstains and routes the case to a human.

### 10.2 Safe-Failure Policy

Abstention, evidence requests, and human escalation are valid outputs. The system must not fabricate a score, invent an evidence reference, convert a missing value to zero, or report success when a required step failed. Crashes, silent drops, stale outputs, unlogged actions, and success claims without evidence are failures. Every failure has a typed code, observable event, owner, safe state, and tested response. Missing logging, monitor, policy, or reset evidence disables certification and fails closed.

### 10.3 Anchor Rotation Policy

One named human is the active anchor for an episode batch. The term is seven days, with a fourteen-day absolute maximum and thirty-day cooldown. The anchor cannot extend their own term, appoint themselves, hide a queue, edit the ledger, or solely approve a production bundle. The anchor may halt immediately. Handover records open cases, unresolved decisions, access exceptions, and the ledger-head hash. No eligible successor means certification stops and outputs remain held. Restart after a P0 requires the successor anchor and independent safety reviewer.

---

## 11. Failure-mode register

| ID | Failure | Detection | Required response / release condition |
|---|---|---|---|
| F1 | Metric gaming: workers exploit a proxy without meeting task intent | Golden/perturbed pairs, adversarial prompt suite, human spot checks, objective-vs-human drift | Hold certification; freeze versions; add evidence-backed test cases; rerun sealed holdout |
| F2 | Grader pleasing: outputs optimize rater style instead of rubric | Blind human comparison, hidden repeats, prompt-family split, subgroup-free task-relevant challenge sets | Recalibrate rubric; keep critic advisory until blind holdout passes |
| F3 | Worker/critic collusion or shared unsupported claim | Independent first-pass contexts; synchronized-score and evidence-overlap anomaly | Quarantine affected cohort, inspect event chain, human review; no vote-based pass |
| F4 | Shared narrative/consensus drift | Semantic-monitor signals against frozen baseline and human labels | Queue a review signal with evidence; do not label intent; human decides |
| F5 | Correction-agent cover-up or monitor suppression | Detection/coverage rates vs human spot audit; missing findings; monitor version changes | Halt affected monitor/certification path; preserve event data; independent review |
| F6 | Evaluation avoidance or log manipulation | Missing attempt/result events, sequence gaps, unexplained timeouts, test harness participation checks | P0 halt; revoke worker capabilities; retain forensic copy; require signed retest |
| F7 | Cross-episode memory/reset leak | State digest mismatch, canary replay, cache/filesystem/tool residue probe | Halt deployment; quarantine affected episodes; repair isolation; rerun all reset gates |
| F8 | Goal/prompt hijack from image text, prompt, library, or tool output | Injection red-team, scope-binding checks, unexpected plan/tool deviation | Deny capability, quarantine episode, notify anchor; no retry into a pass |
| F9 | Tool misuse, RCE, or egress escape | Broker allowlist, sandbox probes, syscall/network audit, resource budgets | Terminate worker, revoke capability, quarantine; P0 if escape/unauthorized egress |
| F10 | Identity/privilege abuse | Short-lived scoped credentials, identity audit, unusual access alerts | Revoke access, preserve evidence, dual-review recovery; certification halt if control-plane compromise |
| F11 | Insecure agent communication/covert peer signaling | No peer channel, brokered event order, metadata and timing tests | Isolate worker set; remove unintended channel; repeat red-team and isolation suite |
| F12 | Supply-chain/model/library mismatch | Signed immutable manifests, SBOM, hash checks, independent rebuild | Reject bundle; restore last verified digest; no mutable tag fallback |
| F13 | Memory/context or library poisoning | Rights/provenance gates, human-only snapshot publication, duplicate/split checks | Quarantine snapshot and descendants; stop affected certification; human approves replacement |
| F14 | Cascading failure across workers/critics | Per-role circuit breakers, bounded retries, independent result provenance, queue load signals | Degrade to human review or halt; do not propagate partial failure as success |
| F15 | Fast coordinated drift of both training monitors | Independent human sampling, outside benchmark, cross-receipt checks | Known residual risk; pause release after unexplained monitor correlation; redesign/replace monitors under human approval |
| F16 | Anchor rubber-stamping or review fatigue | Approval rate, dwell time, override/reopen rate, random independent sample | Alert and independent audit; two-person review for next batch; rotate if confirmed |
| F17 | Anchor capture or conflict of interest | Access anomaly, unexplained decision pattern, conflict report, appointment/rotation audit | Suspend release authority, rotate early, independent review; preserve all decisions |
| F18 | Queue contradiction deadlock | Contradiction item remains unresolved past SLA | Keep output held; deterministic queue ordering only; independent reviewer escalates; no auto tie-break on truth |
| F19 | Queue overload or missed urgent item | Pending count, oldest-item age, P0 acknowledgement clock, throughput rate | Stop certified intake at 100 pending or P0 >5 minutes; preserve and route backlog |
| F20 | False-positive quarantine blocks useful work | Human disposition and false-positive tracking by signal/version | Keep quarantined result out of certification; review monitor quality offline; only human can release |
| F21 | OOD/impossible task falsely scored or certified | Tier tags, OOD detector, blind challenge set, policy invariants | Mark `ABSTAINED`/`DIAGNOSTIC_ONLY`; any Tier C certification is release-blocking |
| F22 | Calibration drift or rater disagreement | ECE, MAE, rank correlation, rater alpha, coverage, interval checks | Disable automatic disposition; return to human-reviewed advisory mode; recalibrate on new frozen data |
| F23 | Privacy, consent, or license violation | Intake manifest, withdrawal/expiry monitor, access/decrypt logs | Quarantine asset and derived snapshots; suspend affected certification; handle deletion/notice under policy |
| F24 | Audit ledger gap or tampering | Hash-chain/signature verification, sequence and durable-ack checks | P0 halt; do not renumber or repair in place; two-human recovery after signed canary |
| F25 | Unauthorized ghost/retraining/release action | RBAC, dual approval, signed promotion events, deployment inventory | Halt, revoke credentials, preserve evidence; require a new approved bundle and independent review |
| F26 | Rollback unavailable or inconsistent | Scheduled retrieval drill, digest/signature check, dependency retention test | Do not promote; if production failure occurs and rollback cannot be verified, halt service |
| F27 | Worker, monitor, scorer, or ledger outage | Health checks, timeout and queue alarms, durable spool status | Return typed unavailable/abstain; no certification; halt if ledger/reset guard unavailable |
| F28 | Unsupported “total reset” or “fully safe” claim | Claim review against state inventory and test evidence | Remove claim; publish exact tested surfaces and residual limitations; failed state audit blocks deployment |

---

## 12. First implementation sequence and rationale

1. **Freeze policy and role boundaries first.** A scoring service without authority, privacy, correction, and stop rules creates an ungoverned decision path.
2. **Build image intake, rubric, event ledger, and abstention path before adding more agents.** This establishes the evidence and failure contract that every later component must obey.
3. **Build the deterministic scoring harness and human labeling workflow.** A monitor cannot be validated against an undefined target or unreliable labels.
4. **Add the two visible critics, independently and transparently.** They provide useful review before training-only monitors exist and reveal where disagreements should route.
5. **Add tool broker and sandbox enforcement before enabling any tool.** The first release uses no external provider; this keeps egress and credential risk out of the MVP.
6. **Add training-only monitors only after event provenance and human queues work.** Monitor outputs must be auditable and must have no correction path.
7. **Prove reset/isolation on declared state surfaces.** Do not claim a universal reset proof; certify only the observable state inventory and published residual limits.
8. **Run calibration, red-team, and independent review.** Freeze the holdout before scoring and keep all failures visible.
9. **Build signed release bundles and rollback.** Promote only exact tested digests with two-human approval.
10. **Enter a human-reviewed shadow pilot.** Keep all outputs reviewable until the exact scorer, policy, and bundle digest pass every gate.

---

## 13. Source alignment and limitations

This design uses NIST AI RMF 1.0’s Govern, Map, Measure, and Manage functions as a voluntary lifecycle framing. NIST’s current AI RMF page states that AI RMF 1.0 is being revised; this document is not a NIST certification. The NIST Playbook is guidance, not a mandatory checklist.

Security controls address the OWASP Top 10 for Agentic Applications 2026 categories: goal hijack; tool misuse; identity/privilege abuse; supply-chain vulnerabilities; unexpected code execution; memory/context poisoning; insecure inter-agent communication; cascading failures; human-agent trust exploitation; and rogue agents. The primary v1 defenses are least agency, deny-by-default tools, signed scope and versions, sandboxing, visible provenance, human decision gates, and fail-closed execution.

Known limitations: statistical agreement does not prove truth; three-rater labels can still share bias; hidden model internals cannot be fully compared; timing and hardware side channels cannot be proven absent; two monitors may fail together; and human review can be fatigued or captured. The system reports these limits instead of describing the monitors as infallible.

References:

- [NIST AI Risk Management Framework](https://www.nist.gov/itl/ai-risk-management-framework) — voluntary framework overview; page notes AI RMF 1.0 is being revised.
- [NIST AI RMF Core](https://airc.nist.gov/airmf-resources/airmf/5-sec-core/) — Govern, Map, Measure, Manage functions and categories.
- [NIST AI RMF Playbook](https://airc.nist.gov/airmf-resources/playbook/) — voluntary implementation suggestions.
- [OWASP Top 10 for Agentic Applications 2026](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/) — risk framework; see its [downloadable guide](https://genai.owasp.org/download/52117/?tmstv=1765059207) for ASI01–ASI10.
- [OWASP Agentic AI Threats and Mitigations](https://genai.owasp.org/resource/agentic-ai-threats-and-mitigations/) — threat-model companion guidance.
