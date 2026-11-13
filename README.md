# THE NIGHTMARE

A local, human-supervised multi-agent harness. The repository's current build brief stages a small Python command-line harness through three named worker roles: Retriever, Analyst, and Formatter. It uses a configurable, loopback-only OpenAI-compatible local model server and Python's standard library only.

## Project status

Phase 2 is implemented locally: each episode routes in order through Retriever, Analyst, then Formatter. Worker results have validated confidence in the range 0–1 and an explicit `COMPLETED` or `ABSTAINED` status. An abstention is a normal episode outcome (CLI exit code 0), not an orchestration error; malformed worker output also fails closed as `ABSTAINED / INVALID_RESPONSE`. The displayed confidence is model-reported and uncalibrated, not a certified score.

No real-model Phase 2 run has yet been verified by this repository. Tests use a local mock HTTP server. Nothing is deployed or certified.

## Run and test

Install no extra packages. From the repository root, set the local Ollama model and pass a self-contained task:

```sh
./run.sh --model llama3.2:1b 'Repeat exactly this token: NIGHTMARE_PHASE2_OK'
```

The default endpoint is `http://127.0.0.1:11434/v1`; Ollama must be running locally. The output reports the episode status and which roles ran. `COMPLETED` includes the formatted answer; `ABSTAINED` names the role and reason. A request that needs external or source-backed evidence may abstain because no retrieval library is present yet.

Run the deterministic test suite with:

```sh
python3 -m unittest discover -s tests -v
```

## Scope and next phase

The Retriever has no search tool, external network, or library access. It may use only information explicitly present in the task; it must abstain rather than invent retrieval results, facts, or citations when outside evidence is required. The library/retrieval feature remains Phase 3. This phase also does not add reset auditing, logs, a human review queue, export, the visible critic pair, or any production scoring/certification capability.

The local worker-phase numbering follows the original repository build brief. It is distinct from the phase numbering in [the long-term implementation specification](docs/implementation-spec.md), where Phase 2 refers to a future visible critic pair. Neither the broader specification's scores, thresholds, policies, and retention defaults nor this local harness establish measured system performance.

## Long-term design intent

- Workers operate in isolated, stateless episodes and use a frozen, read-only library.
- Scoring is evidence-based and may abstain; missing evidence is never treated as a zero or a pass.
- Human operators can inspect monitor provenance, issue or reject corrections, and stop the system.
- Corrections are attributed to the human who made the decision; monitor influence is recorded.
- Model, rubric, library, and build artifacts are versioned and promoted by immutable digest.
- Deployment fails closed when reset, audit, security, calibration, or provenance gates fail.

Monitoring and human review are disclosed to the accountable operator. The design does not use hidden queues or disguise machine influence as randomness or independent human judgment.

The broader design proposal remains limited to still-image quality review and excludes identity matching, biometric recognition, demographic or protected-trait inference, personal-photo evaluation, online learning, autonomous correction, and machine-issued certification. Do not describe this local harness as production-ready; applicable gates and human approvals in the full specification remain outstanding.
