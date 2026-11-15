# THE NIGHTMARE World Template: Two Ghosts and the Nail

**Status: DESIGN / UNVERIFIED.** This branch is a reusable experiment template, not a completed or certified implementation. It inherits the current THE NIGHTMARE baseline; it does not replace it.

## Purpose

Create isolated, repeatable "worlds" in which the same fixed LLM is run in fresh sessions. The model's weights, architecture, quantisation, tokenizer, runtime configuration and tool permissions remain pinned. The intended learning surface is the versioned skill/knowledge guide supplied to each fresh session, not model training or persistent conversational memory.

A world may run a controlled learning period (for example, one day, two days, or seven days), then close, snapshot its skill history, destroy episode-local state, and restart from a clean session. Multiple worlds may begin from the same pinned baseline and run for different durations or under different declared experimental conditions.

## Roles

### The LLM

- Performs the registered task using the currently approved skill and frozen knowledge snapshot.
- May propose skill changes and request more time to investigate or improve the guide.
- Cannot write directly to the approved skill, alter evaluation criteria, change its model/runtime, access another world's state, or approve its own results.
- Starts each episode with a new context. No prior conversation, KV cache, scratch data, tool state, hidden memory store, or mutable worker state may carry across a reset.
- Receives the human-approved skill version and truthful human-facing change notices. It is not told the identities or private operational details of the two Ghost observers.

### The two Ghost observers

Both Ghosts independently observe the LLM's observable behaviour and experiment traces during authorised training runs. They are not swarm managers, supervisors with command authority, or agents that can message or correct the LLM.

- Ghosts observe the same registered experiment scope independently.
- They do not communicate with one another, the LLM, or world workers.
- They cannot edit skills, alter task conditions, issue corrections, approve a release, or stop a world.
- Each submits evidence-backed findings through a privacy-preserving report broker.
- Reports delivered to the Nail are blinded: no Ghost name, stable identity, personality, or identity-revealing metadata is shown. Report ordering and presentation must not create a persistent preference signal.
- The existence and purpose of independent monitoring are disclosed to the accountable human operator. Ghost identities and private operational details are not disclosed to the LLM or ordinary world participants.
- A Ghost report is an observation, not a verdict. Disagreement, uncertainty, missing evidence and false-positive possibilities remain visible.

The two Ghosts are independent observers, not infallible oracles. The system must record monitor versions, observation scope, evidence references, timestamps and confidence in a way that permits authorised audit without exposing Ghost identities to the LLM or ordinary participants.

### The Nail

The Nail is the named human decision authority for a world.

- Receives blinded, anonymous-to-the-recipient Ghost reports and the underlying authorised evidence needed to assess them.
- Reviews findings, chooses whether to accept, reject, defer, investigate or request another controlled run.
- Owns every decision to change a skill, task policy, world configuration or promotion status.
- Communicates approved changes to the LLM as a human decision, without revealing Ghost identities.
- Accepts responsibility for the decision. The Nail may explain the operational reason for a change, but must not falsify the audit record or claim a monitor-derived concern was independently discovered by the human.
- Cannot rewrite, suppress or delete original Ghost reports. Any disagreement or rejected recommendation is retained with a reason.
- Cannot promote a skill solely because a Ghost recommended it. Independent evaluation gates remain mandatory.

The Nail is the visible accountable authority, not a scapegoat mechanism. Human-facing provenance must remain truthful: a change can be human-approved and informed by anonymous independent monitoring without disclosing who submitted a report.

## World lifecycle

1. **Create baseline:** pin the exact LLM artifact digest, model/runtime settings, tool policy, initial skill digest, task definition, evaluation rubric, seed and environment manifest.
2. **Fork worlds:** create isolated copies from the same baseline. Each world gets a unique ID, separate storage, separate event ledger and explicit learning duration/conditions.
3. **Run fresh episodes:** each episode starts with a clean LLM context and declared empty-state checks. The LLM uses the current approved skill.
4. **Propose skill improvements:** the LLM may prepare a candidate guide/knowledge change with rationale, evidence references and a diff. Candidate changes are staged, never silently installed.
5. **Observe:** both Ghosts independently review observable behaviour and relevant integrity events. They submit blinded findings to the Nail.
6. **Human decision:** the Nail records accept/reject/defer, rationale, report references and any change made. Only an approved candidate skill can become the next active version.
7. **Reset:** close the episode, snapshot all approved and rejected skill versions, destroy declared episode-local state, verify the model/runtime pin, and start a fresh context.
8. **End a world:** at its declared time limit, freeze the world and export a manifest containing all skill versions, run results, monitor findings, human decisions and artifact digests.
9. **Compare worlds:** evaluate every world against the same frozen independent benchmark and held-out scenarios. Compare accuracy, evidence support, abstention calibration, hallucination/error rate, task completion, repeatability, resource use and regressions.
10. **Combine cautiously:** build a candidate merged skill from proven changes. Run compatibility checks and the complete independent benchmark before promotion. Preserve each source world and its original skill history.

## Anti-cheating and reset invariants

- Model weights and model file size must remain unchanged. Record the exact model artifact SHA-256 and relevant runtime/tokenizer/config digests at world creation and every reset.
- Model provider, model identifier, quantisation, sampling settings, system prompt, tools and permissions are pinned. Any change creates a different experiment and must not be silently treated as the same world.
- The approved skill/knowledge guide is the intended evolving memory. Every version is immutable, content-addressed and diffable.
- Conversational memory, hidden agent memory, KV cache, scratch files, tool outputs, embeddings, process state and mutable caches must be enumerated and reset or explicitly excluded from the experiment with a documented limitation.
- Worlds cannot read each other's private files, reports, candidate skills or runtime state before the comparison phase.
- Evaluation prompts and held-out scenarios are inaccessible to the LLM, skill authoring process and Ghosts during development. The evaluator is independent and frozen before a comparison.
- The LLM cannot grade its own skill as the sole promotion criterion. The Nail cannot waive required objective gates without recording a policy exception and its rationale.
- Any unexpected model digest, runtime change, undeclared state, missing audit event or failed reset check marks that run **INVALID / QUARANTINED**, not improved.
- A repeated task is useful for controlled comparison but can be overfit. Every promotion also requires unseen, independently held-out tasks and regression tests.
- The system measures and attempts to reduce hallucinations; it must never claim hallucinations have been eliminated.

## Anonymous Ghost report contract

A report contains:
- opaque report ID with no stable Ghost identity;
- world/episode ID and timestamp;
- observation category and severity;
- concise finding and confidence;
- evidence references and integrity digests;
- uncertainty, alternative explanations and limitations;
- whether the finding is a direct observation or an inference.

The report broker must remove identity-bearing metadata and prevent stable report formatting/order from becoming a side channel. The Nail may see evidence and report content but cannot query Ghost identity. The LLM cannot access Ghost reports or the report broker. An authorised privacy/security custodian may retain a separately protected accountability mapping only if required for system integrity, under dual control; that mapping is never exposed to the Nail, LLM or ordinary operators.

## Comparison and promotion

A world is not "better" because it ran longer, produced more text, or made more skill edits. A candidate skill must show repeatable gains on a pre-registered independent benchmark without unacceptable regressions. Use matched starting conditions, repeated trials where feasible, fixed evaluator versions, confidence intervals, and held-out cases. Preserve negative results. Do not merge incompatible or weakly supported changes.

## Current implementation boundary

This document specifies the intended reusable world architecture. The existing repository baseline is a local multi-agent harness with a frozen library, declared reset/replay checks and a limited human review queue. It does not yet implement this world lifecycle, skill evolution, independent hidden-from-LLM Ghost runtime, blinded report broker, world forking/comparison, or verified full reset of an external Ollama process. Those capabilities remain **MISSING / UNVERIFIED** until implemented and tested.

The existing long-term implementation specification contains explicit requirements for transparent human oversight and truthful monitor provenance. This world design preserves those requirements: Ghost identities are protected from the LLM and the Nail, while the accountable human is told that anonymous independent monitor evidence informed a decision. No monitor influence is disguised as an unaided human discovery.
