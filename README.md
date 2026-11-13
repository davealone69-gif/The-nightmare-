# THE NIGHTMARE

A local, human-supervised multi-agent harness. The repository's current build brief stages a small Python command-line harness through three named worker roles: Retriever, Analyst, and Formatter. It uses a configurable, loopback-only OpenAI-compatible local model server and Python's standard library only.

## Project status

Phases 1–3 are implemented locally; Phase 3 is awaiting the owner's test in Termux. Episodes route through Retriever, Analyst, then Formatter. The Retriever searches only a frozen, read-only text library outside the code tree, verifies source hashes, returns matching excerpts with stable IDs, and abstains when requested evidence is unavailable. Completed answers show their local source paths and are classified `CITED`, `UNCITED`, `INVALID`, or `NOT_REQUIRED`; uncited and unknown-source citations are visibly warned. Citation classification checks reference IDs, not whether every claim is true. Worker confidence remains uncalibrated, not a certified score.

Tests use a local mock HTTP server, including an end-to-end cited library query. No real-model Phase 3 or Termux run has yet been verified. Nothing is deployed or certified.

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

The sample query should produce a cited answer with `[DOC-DEMO-FERN-GUIDE-MD]` and a `sources:` entry pointing to `demo-fern-guide.md`. If the Analyst abstains on a source-backed query that has retrieved passages, the harness returns a clearly labeled excerpt from the verified local passage with its manifest citation, marks `answer_mode: EXTRACTIVE_FALLBACK`, and the Formatter passes it through unchanged. This fallback adds no answer claims beyond the excerpt. If the model completes but omits a citation, the output explicitly reports `citation_status: UNCITED` and `warning: ANSWER_NOT_CITED`; unknown source IDs are flagged `INVALID`. A source-backed query with no matching passage abstains before any model call. Live/current requests such as current weather still abstain because the local library is not a live-data service.

For your Phase 3 owner test in Termux, run the deterministic suite and then the same example query. The demo seeder is one-time; if you already ran it above, skip that line below:

```sh
python3 -m unittest discover -s tests -v
python3 examples/seed_demo_library.py
./run.sh --model llama3.2:1b 'According to the local guide, what light does a tree fern need? Cite the source.'
```

The seeder writes only under the configured external data directory and will stop rather than overwrite an existing library. Library sources are plain UTF-8 `.md` or `.txt` files (each at most 1 MiB; total source size at most 16 MiB). For a custom corpus, place those files in `data_dir/library/` and run `python3 tools/freeze_library.py`; it writes a manifest of SHA-256 digests and makes the snapshot files/directories read-only. The runtime never writes to the library and rejects writable, changed, missing, unlisted, or malformed snapshot content. Citation paths are relative to the library directory; their full content digests appear in the output.

Run the deterministic test suite with:

```sh
python3 -m unittest discover -s tests -v
```

## Scope and next phase

Phase 3 adds lexical retrieval from a frozen, manifest-verified local corpus and reference-ID validation. If a source-backed Analyst abstains despite retrieved evidence, the deterministic fallback quotes the retrieved excerpt and uses its verified source ID; the Formatter does not rewrite it. The library is read-only to runtime workers; the offline freezer is an explicit setup utility, not an automatic ingestion path. Retrieval is deterministic term overlap, not semantic search, and a valid citation ID on a model-generated answer does not prove that every claim is supported. No live network retrieval is available. Zero-confidence model completions become `LOW_CONFIDENCE` abstentions. Reset auditing, sealed logs, a human review queue, export, the visible critic pair, and production scoring/certification remain out of scope; Phase 4 and later have not been implemented.

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
