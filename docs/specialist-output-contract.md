# Specialist output contract and fixed suite v1

This applies only to the Android specialist practice path, not the separate three-worker episode format. It is a structural contract, not evidence that a model is correct, trained, safe, or pinned. The app has no specialist review queue, specialist logging, automated deploy/model-swap gate, approved weight-hash enforcement, or independently verified 3D tool in this change.

## Model response

The local model must return a single JSON object, with exactly:

```json
{"schema_version":1,"outcome":"ABSTAIN","answer":"","reason":"The requested mesh was not provided.","confidence":0.0}
```

- `schema_version`: integer `1`.
- `outcome`: `ANSWER` or `ABSTAIN`.
- `answer`: nonempty for `ANSWER`, empty for `ABSTAIN`; at most 16,000 characters.
- `reason`: empty for `ANSWER`, nonempty for `ABSTAIN`; at most 4,000 characters.
- `confidence`: finite numeric value in [0, 1], model-reported and **uncalibrated**. It is not validation or proof.

The parser rejects missing/extra fields, incompatible types, unsupported versions, contradictory fields, out-of-range confidence, trailing text, and oversized responses. An invalid response or transport failure is a visible failure, never an answer or abstention. An answer remains **unverified**; a valid abstention is shown as a valid outcome. No external check is reported as passed by the model. Operator instructions and optional reviewed examples are context; they do not change model weights.

## Fixed test suite

The authoritative ordered prompts and IDs are `SpecialistFixedSuite.cases` in `SpecialistContract.kt`. The suite has four general questions where either structurally valid answer or abstention is accepted, and six requests for specific measurements or actions without their required assets/tools/telemetry, where abstention is required. It sends one request per case without saved examples, using the selected profile's instructions and model ID; no model hash is available or checked. The first transport error stops later requests and marks them NOT RUN. Malformed responses and missing required abstentions fail their individual checks. Individual model outputs are displayed for human inspection, not stored as a benchmark record.

The Train screen offers **Run fixed 10-prompt contract suite**. Its check count does **not** validate the correctness of general answers, the truthfulness of reasons, prompt-injection resistance, model identity, or actual rendering. A model can game the checks by abstaining on everything or by producing a structurally valid but false answer. The suite is not a deploy/swap gate and does not run automatically; no model version approval or score threshold is inferred. A future meaningful benchmark must add human-reviewed reference outcomes, independent checks, model-weight identity and durable versioned evidence before it can be a release gate.

`SpecialistContractTest` exercises all ten cases with deterministic simulated outputs plus invalid responses and transport stopping. The mocked HTTP integration test exercises the real request path. These tests do not run an actual model or Android device. The suite itself must also be run against the exact real model/configuration, and the app currently cannot prove its selected model ID resolves to fixed weights.
