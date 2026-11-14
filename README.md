# THE NIGHTMARE

A local, human-supervised multi-agent harness. The repository's current build brief stages a small Python command-line harness through three named worker roles: Retriever, Analyst, and Formatter. It uses a configurable, loopback-only OpenAI-compatible local model server and Python's standard library only.

## Project status

Phases 1–5 are integrated; Phase 6 portable export is implemented in this feature-branch candidate. Phase 3 is still awaiting the owner's test in Termux. Phase 4 adds a declared in-process reset-state audit and an opt-in `--audit-replay --seed N` path that compares repeated harness-state snapshots. Phase 5 adds a limited local human-review queue. Episodes route through Retriever, Analyst, then Formatter. The Retriever searches only a frozen, read-only text library outside the code tree, verifies source hashes, returns matching excerpts with stable IDs, and abstains when requested evidence is unavailable. Completed answers show their local source paths and are classified `CITED`, `UNCITED`, `INVALID`, or `NOT_REQUIRED`; uncited and unknown-source citations are visibly warned. Citation classification checks reference IDs, not whether every claim is true. Worker confidence remains uncalibrated, not a certified score.

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

## Portable export

Create a portable folder and a reproducible ZIP with one command:

```sh
python3 tools/export_portable.py
```

The default outputs are `dist/the-nightmare-portable/` and `dist/the-nightmare-portable.zip`. Use `--output-dir PATH` to choose another output directory; the exporter refuses to overwrite an existing output. It discovers project files dynamically, while excluding tests, logs, caches, build/export output, sensitive local paths, and the configured runtime data directory. Recognizable secret formats and secret-like field assignments abort the export, including shell `export` and Python `os.environ[...] = ...` assignments to credential-like names; this textual scan is conservative, not an exhaustive secret detector. Source files are read through no-follow descriptors, and the captured bytes are the bytes written to the bundle. Immediately before publication, the exporter rechecks included file content and metadata plus the names, type, and identity of entries in scanned directories, including excluded entries. This is optimistic capture, not an atomic filesystem snapshot or a source lock: a live change after the final check can go undetected. Both outputs preserve the launcher’s executable bit; ZIP timestamps and file ordering are normalized for reproducibility. The bundle uses Python’s standard library and still needs a local OpenAI-compatible server and model to run real tasks; no provider is contacted by the exporter.

The folder and ZIP are separate paths and are **not atomically visible as a pair**. Consumers must treat them as unpublished unless `the-nightmare-portable.manifest.json` exists and validates: its `version`, `folder`, `archive`, `tree_sha256`, `archive_sha256`, and `file_count` fields bind both outputs, and the ZIP's contents must hash to the same tree as the folder. In Python, `tools.export_portable.verify_export_pair(output_dir)` checks this contract and rejects nested symlinks and non-regular entries. A crash between the two renames leaves no valid completion manifest; recovery finalizes a matching complete pair or rolls back a partial one. A crash before the durable transaction marker is installed can leave an unmarked `.nightmare-export-*` staging entry. The exporter does not automatically delete such unclaimed entries; it preserves them and refuses a retry until they have been inspected and manually removed. The exporter fsyncs staged files/directories, the transaction/completion records, and publication directories. For output-directory creation, it fsyncs the output and each newly created directory through the nearest pre-existing parent, but deliberately does not open higher unchanged ancestors, which may be protected in app-sandboxed environments such as Termux. Failure to fsync any required directory aborts the export; higher unchanged ancestors are outside this flush boundary, and durability still depends on the filesystem and storage honoring `fsync`.

On Linux, publication uses `renameat2(RENAME_NOREPLACE)` so a competing process that creates an output leaf is not overwritten. The destination lock serializes cooperating exporters only; non-cooperating actors can still modify or replace the output directory itself or alter published contents afterward. A passing digest check means the bytes matched the recorded hashes at check time; it does not make the outputs immutable or prevent later changes. Consumers should validate the manifest and both hashes when opening the pair. On systems without Linux `renameat2` or no-follow descriptor support, publication fails closed rather than using an overwrite-prone fallback.

The ZIP stores the launcher's Unix executable-bit metadata. Some extractors, including Python's `zipfile.extractall()`, do not apply it when extracting; if needed, start the extracted bundle with `sh run.sh`.

## Scope and next phase

Phase 3 adds lexical retrieval from a frozen, manifest-verified local corpus and reference-ID validation. The library is read-only to runtime workers; the offline freezer is an explicit setup utility, not an automatic ingestion path. Retrieval is deterministic term overlap, not semantic search, and a valid citation ID does not prove that a claim is supported. No live network retrieval is available. Zero-confidence model completions become `LOW_CONFIDENCE` abstentions. Phase 4 implements only the limited declared-state audit, not a system-wide reset proof; the daily canary and deployment gates remain unimplemented. Phase 5 implements only the disclosed local review queue, not a production human-review system. Phase 6 adds the portable exporter; its independent review was conditional PASS. Secret scanning remains heuristic, live source capture is optimistic rather than atomic, the folder/ZIP pair is not atomically visible, and Termux and power-loss behavior remain unverified. The visible critic pair and production scoring/certification also remain outstanding.

The local worker-phase numbering follows the original repository build brief. It is distinct from the phase numbering in [the long-term implementation specification](docs/implementation-spec.md), where Phase 2 refers to a future visible critic pair. Neither the broader specification's scores, thresholds, policies, and retention defaults nor this local harness establish measured system performance.

## Long-term design intent

- Workers operate in isolated, stateless episodes and use a frozen, read-only library.
- Scoring is evidence-based and may abstain; missing evidence is never treated as a zero or a pass.
- Human operators can inspect monitor provenance, issue or reject corrections, and stop the system.
- Corrections are attributed to the human who made the decision; monitor influence is recorded.
- Model, rubric, library, and build artifacts are versioned and identified by recorded content digests; a digest identifies checked bytes but does not enforce immutability.
- Deployment fails closed when reset, audit, security, calibration, or provenance gates fail.

Monitoring and human review are disclosed to the accountable operator. The design does not use hidden queues or disguise machine influence as randomness or independent human judgment.

The broader design proposal remains limited to still-image quality review and excludes identity matching, biometric recognition, demographic or protected-trait inference, personal-photo evaluation, online learning, autonomous correction, and machine-issued certification. Do not describe this local harness as production-ready; applicable gates and human approvals in the full specification remain outstanding.


## Native Android app

A separate native Kotlin Android client is included in this feature branch under [`android/`](android/README.md). It preserves the Python standard-library Termux CLI and its data while providing native task, review, frozen-library setup/repin, and local portable-export workflows. It uses only a loopback local Ollama service; the portable export is explicitly user-triggered and shares only after pair verification. SAF does not expose the Python exporter's descriptor-relative `O_NOFOLLOW` primitive, so native filesystem-import/export parity is partial; see the Android guide for the exact boundary. Android targets API 37, but installation and exercise on the Samsung Galaxy S24 SM-S921B remain unverified. The broader detector, daily canary, critic, and deployment gates are still not implemented.
