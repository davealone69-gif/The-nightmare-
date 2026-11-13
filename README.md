# THE NIGHTMARE

A local, human-supervised multi-agent harness. Phase 1 is a small Python command-line skeleton that sends one task to a configurable, loopback-only OpenAI-compatible LLM server. Later phases add workers, retrieval, reset auditing, human queues, and export.

## Project status

Phase 1 code is present under `agent_harness/`. Its tests use a local mock HTTP server; a real-model answer has not yet been verified because no local LLM server/model was available in the build environment. The harness does not include or load a model, and nothing is deployed or certified. The broader specification’s scores, thresholds, policies, and retention defaults remain proposed engineering baselines, not measured system performance.

## Long-term design intent

- Workers operate in isolated, stateless episodes and use a frozen, read-only library.
- Scoring is evidence-based and may abstain; missing evidence is never treated as a zero or a pass.
- Human operators can inspect monitor provenance, issue or reject corrections, and stop the system.
- Corrections are attributed to the human who made the decision; monitor influence is recorded.
- Model, rubric, library, and build artifacts are versioned and promoted by immutable digest.
- Deployment fails closed when reset, audit, security, calibration, or provenance gates fail.

Monitoring and human review are disclosed to the accountable operator. The design does not use hidden queues or disguise machine influence as randomness or independent human judgment.

## Scope of the current implementation

Phase 1 is deliberately limited to one worker and one local LLM request per task. It has no multi-worker routing, confidence scoring, abstention handling, retrieval, episode reset/audit, sealed logs, human queue, or export yet. Do not treat those later-phase requirements as implemented. The broader design proposal remains limited to still-image quality review and excludes identity matching, biometric recognition, demographic or protected-trait inference, personal-photo evaluation, online learning, autonomous correction, and machine-issued certification.

## Specification

See [the full implementation specification](docs/implementation-spec.md) for architecture, API and event schemas, scoring and calibration gates, human governance, security and reset controls, phased build order, acceptance tests, and the failure-mode register.

## Current decision gate

Phase 1’s remaining acceptance check is a run against the owner’s actual local model server. After that, stop for the owner’s test result before beginning Phase 2. Do not describe the broader system as production-ready until each applicable gate in the specification has reproducible evidence and the exact release bundle has human approval.
