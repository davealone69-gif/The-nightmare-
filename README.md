# THE NIGHTMARE

A local, human-supervised multi-agent harness. The repository's current build brief stages a small Python command-line harness through three named worker roles: Retriever, Analyst, and Formatter. It uses a configurable, loopback-only OpenAI-compatible local model server and Python's standard library only.

## Project status

Phases 1–5 are integrated locally; Phase 3 is still awaiting the owner's test in Termux. Phase 4 adds a declared in-process reset-state audit and an opt-in `--audit-replay --seed N` path that compares repeated harness-state snapshots. Phase 5 adds a limited local human-review queue. Episodes route through Retriever, Analyst, then Formatter. The Retriever searches only a frozen, read-only text library outside the code tree, verifies source hashes, returns matching excerpts with stable IDs, and abstains when requested evidence is unavailable. Completed answers show their local source paths and are classified `CITED`, `UNCITED`, `INVALID`, or `NOT_REQUIRED`; uncited and unknown-source citations are visibly warned. Citation classification checks reference IDs, not whether every claim is true. Worker confidence remains uncalibrated, not a certified score.

Tests use a local mock HTTP server, including an end-to-end cited library query. No real-model Phase 3 or Termux run has yet been verified. Nothing is deployed or certified.

The reset audit covers only the harness's declared in-process prompt context, results, and worker counters; it cannot inspect model-server or operating-system state, and its seed is not forwarded to the model server. Daily canary and deployment gates remain unimplemented. The local Phase 5 review queue has two separate append-only monitor journals and a human approve/reject/edit CLI. Monitoring and machine authorship are disclosed; human decisions are recorded separately, and only approved findings or explicitly human-authored edits can be prepared as later-episode context. Phase 5 CLI actions require the `--phase5` prefix so task text beginning with words like `review` or `monitor` stays task text. This is not OS-level process isolation, authenticated reviewer identity, or a cryptographically tamper-evident ledger. See [the Phase 4 audit notes](docs/phase4-reset-state-audit.md) and [the Phase 5 local queue guide](docs/phase5-local-queue.md).

## Run and test

Install no extra packages. From the repository root, set the local Ollama model and pass a self-contained task:

```sh
./run.sh --model llama3.2:1b 'Repeat exactly this token: NIGHTMARE_PHASE2_OK'
```

The default endpoint is `http://127.0.0.1:11434/v1`; Ollama must be running locally. No cloud API or extra package is used. The configured `data_dir` is relative to `config.json` and must remain outside the project; the frozen library lives at `data_dir/library/`.

To create the optional demo corpus (the command refuses to overwrite an existing library) and run an example source-backed query:

```sh
python3 examples/seed_demo_library.py
./run.sh --model llama3.2:1b 'According to the local guide, what light does a tree fern need? Cite the source.'
```

The sample query should produce a cited answer with `[DOC-DEMO-FERN-GUIDE-MD]` and a `sources:` entry pointing to `demo-fern-guide.md`. The local model must follow the citation prompt; if it omits a citation, the output explicitly reports `citation_status: UNCITED` and `warning: ANSWER_NOT_CITED`. Unknown source IDs are flagged `INVALID`. A source-backed query with no matching passage abstains before any model call. Live/current requests such as current weather still abstain because the local library is not a live-data service.

The freezer stores the manifest's snapshot ID in `config.json`, outside the data library, and the runtime requires that pin when a library exists. If you already froze a library with an older version that did not store a pin, run `python3 tools/freeze_library.py --pin-existing` once to validate that snapshot and pin it without changing its files; only do this when you trust the existing library contents. A different existing pin is never silently replaced; use `--repin` only after reviewing and explicitly approving the new snapshot, for example `python3 tools/freeze_library.py --pin-existing --repin`. When the source-backed Analyst abstains after verified passages were retrieved, the harness quotes the excerpt with its verified citation, labels the result `answer_mode: EXTRACTIVE_FALLBACK`, and the Formatter passes it through unchanged; this fallback adds no claims beyond the excerpt.

To compare declared harness state across two runs (the seed is audit metadata, not a seed sent to the model server):

```sh
./run.sh --audit-replay --seed 713 --model llama3.2:1b 'Repeat this fixed task'
```

For your Phase 3 owner test in Termux, run the deterministic suite and then the same example query. The demo seeder is one-time; if you already ran it above, skip that line below:

```sh
python3 -m unittest discover -s tests -v
python3 examples/seed_demo_library.py
./run.sh --model llama3.2:1b 'According to the local guide, what light does a tree fern need? Cite the source.'
```

The seeder writes only under the configured external data directory and will stop rather than overwrite an existing library. Library sources are plain UTF-8 `.md` or `.txt` files (each at most 1 MiB; total source size at most 16 MiB; the manifest is limited to 1 MiB). For a custom corpus, place those files in `data_dir/library/` and run `python3 tools/freeze_library.py`; it writes a manifest of SHA-256 digests, makes the sources/manifest/directories read-only, and only then saves the config pin. If saving that pin fails, the corpus stays frozen but unpinned and ordinary loading refuses it; after checking the contents, retry with `python3 tools/freeze_library.py --pin-existing` to save the pin without rewriting library files. The runtime never writes to the library and rejects writable, changed, missing, unlisted, or malformed snapshot content. Citation paths are relative to the library directory; their full content digests appear in the output.

The manifest ID is recomputed from all source metadata and checked against the `library_snapshot_id` in `config.json`. This detects metadata changes and coordinated library rewrites that do not also alter the config pin. The config is the trust anchor, so protect it too; this is a local hash check, not a digital signature or protection against a fully privileged attacker. A valid citation ID on a model-generated answer does not prove every claim is supported. The runtime reads manifests and sources through descriptor-relative `O_NOFOLLOW` opens, rejects FIFOs, and checks file identity before and after reading. Run the offline freezer only with exclusive access to a private local library tree; its path-based inventory and `chmod` still leave a concurrent-symlink replacement risk during setup.

Run the deterministic test suite with:

```sh
python3 -m unittest discover -s tests -v
```

## Scope and next phase

Phase 3 adds lexical retrieval from a frozen, manifest-verified local corpus and reference-ID validation. The library is read-only to runtime workers; the offline freezer is an explicit setup utility, not an automatic ingestion path. Retrieval is deterministic term overlap, not semantic search, and a valid citation ID does not prove that a claim is supported. No live network retrieval is available. Zero-confidence model completions become `LOW_CONFIDENCE` abstentions. Phase 4 implements only the limited declared-state audit, not a system-wide reset proof; the daily canary and deployment gates remain unimplemented. Phase 5 implements only the disclosed local review queue, not a production human-review system. Portable export is not integrated here because Phase 6's post-fix independent review has not yet been reported. The visible critic pair and production scoring/certification also remain outstanding.

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
