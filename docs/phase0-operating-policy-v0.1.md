# Phase 0 Operating Policy v0.1

**Status:** Conservative implementation defaults recorded. Human owner approval and deployment configuration are still required. This policy does not certify the system.

## 1. Human authority and ghost re-enablement

Only the named human project owner may authorize ghost re-enablement. The authorization must identify the exact ghost code hashes, model weights hash, skill snapshot, fixed-suite results, purpose and expiry. No agent, anchor process, ghost, critic or orchestration code may self-authorize or extend the authorization. Until a named human identity is configured and the authorization is sealed, ghost re-enablement is blocked.

## 2. One-person anchor and bounded slices

The current operating mode is **one human anchor**. There is no fictional overlap handover and no automatic replacement anchor. The sealed log is divided into bounded sequence-number slices. The anchor receives only a blinded projection of the assigned slice; raw provenance remains in the sealed evidence store for authorised independent oversight. A missing independent reviewer does not grant the anchor access to raw provenance.

The solo anchor must re-commit at least every 30 calendar days by reopening the current slice, rereading it, testing the stop control and explicitly affirming responsibility. The re-commitment record is appended to the sealed log before the due timestamp is advanced. A missed deadline pauses new episodes.

## 3. Rubber-stamp trigger

Calculate approval rate over the **most recent 20 completed anchor decisions**. Count approve and correct as approvals; count reject and defer as non-approvals. Fewer than 20 decisions is insufficient evidence and cannot trigger this statistical rule. A rate strictly greater than 0.90 across all 20 decisions triggers immediate pause, independent spot-audit and a required anchor re-commitment. The trigger is not proof of capture.

## 4. Event-driven rotation in solo mode

The following events fire an immediate pause: (a) the 20-decision rubber-stamp threshold; (b) any failed independent spot-audit; (c) an unresolved Mandela/Matrix contradiction; (d) sealed-log chain, slice-commitment or access-control failure; (e) unauthorised raw-log access; (f) the anchor records “this feels wrong” or invokes stop; or (g) missed monthly re-commitment. Because there is only one anchor, the system cannot silently rotate to a nonexistent person. It stays paused until a separately designated human reviewer investigates and the project owner explicitly re-authorizes operation. If no reviewer is available, it stays paused.

## 5. Retraining and skill evolution cadence

No calendar-based retraining. Review is event-driven after a human-approved failure pattern or a scheduled evaluation failure. The system does not learn online. A new skill capsule requires fixed-suite comparison and explicit human approval. Any model-weight change is a new model artifact and requires separate approval. Ghosts remain disabled outside explicitly authorized training sessions.

## 6. Latency and cost

No production latency or cost multiplier is approved yet. Development runs must record per-role latency, total wall time, token usage where available, model/provider identity and estimated cost where available. No production commitment or client SLA may be made until the owner approves a measured budget. Missing provider cost data is reported as unknown, never zero.

## 7. Initial domain boundary

The first candidate domain is **still-image quality review of synthetic or explicitly consented, non-identifiable 3D renders and assets**. Exclude identity matching, biometrics, demographic/protected-trait inference and personal-photo evaluation. The candidate domain remains blocked until the Phase 2 grader, data provenance/licensing, human calibration and held-out evaluation gates pass.

## 8. Meaning of “not certified”

“Not certified” means **not approved for production deployment, client-facing quality guarantees or automated acceptance/rejection**. It does not automatically mean the item is bad. The result must abstain or remain quarantined for human review, with uncertainty and missing evidence visible. Only a separately approved human workflow may release a reviewed result.

## 9. Sealed-log location and access

The production target is a private, encrypted local data volume under the configured application data root, in a dedicated sealed/ directory outside every reset target, source checkout, export bundle and shared cloud-sync folder. The raw chain is append-only at the application layer, backed up to a separately controlled append-only destination, and access is audited. Raw-log readers are limited to the named human project owner and a separately designated independent reviewer. The anchor receives only blinded, bounded projections. No independent reviewer is configured yet, so raw-log access policy is not operationally complete and production deployment is blocked. A hash chain alone is not encryption or access control.

## 10. “This feels wrong” escalation

The anchor's stop action or recorded “this feels wrong” statement immediately: (1) pauses episode admission and all automated corrections; (2) freezes affected outputs and capsule promotions; (3) seals the trigger, current bounded slice and relevant raw evidence; (4) preserves both ghost findings without choosing a winner; (5) requests independent human review. The system remains paused until a reviewer whose configured human identity is distinct from the project owner records a finding, and the project owner explicitly re-authorizes with evidence references. The resume record is appended to the sealed log before the pause is cleared. If no reviewer is available, the pause remains in force. No model or ghost may overrule this stop.

## 11. THE WORLD deferral boundary

**THE WORLD** is the separate persistent simulated-world experiment in which model agents inhabit a world, with a ghost-observed variant and a clean comparison branch; it is deferred to keep THE NIGHTMARE's governance and verification work isolated, and may resume only after NIGHTMARE Phases 0–8 pass, its owner-approved handover is complete, and a separate scope/branch is explicitly authorized.

## Deployment boundary

These are conservative defaults for implementation, not evidence that the controls exist. Phase 0 is not complete until the owner approves this policy, a human identity and independent reviewer are configured, encrypted storage/access controls are implemented, and the stop/pause path is tested. Until then, the system remains a development scaffold.
