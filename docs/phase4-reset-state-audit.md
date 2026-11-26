# Phase 4: Local reset-state audit

The Phase 4 work adds a fail-closed reset boundary to the existing three-worker episode orchestrator. It audits three mutable state surfaces owned by this Python harness:

- `prompt_context`: the current episode's task text;
- `tool_results`: results collected within the current episode; and
- `worker_counters`: per-role calls within the current episode.

An existing `data_dir/library` must be opened with a pin matching `snapshot-[0-9a-f]{16}`. The CLI reads `library_snapshot_id` from config and refuses an existing library without it. Direct API callers must pass `FrozenLibrary(path, expected_snapshot_id=...)`; searching an existing library without that expected ID raises `LibraryError` with reason `LIBRARY_UNPINNED`, while a missing directory remains `LIBRARY_UNAVAILABLE`. A manifest ID that differs from the supplied pin is rejected.

Both `examples/seed_demo_library.py` and `tools/freeze_library.py` persist the newly frozen manifest ID into the selected config as `library_snapshot_id`. Thus the README's demo and custom-corpus setup flows pin the library before the follow-up CLI query.

If manifest creation and read-only locking succeed but config-pin persistence fails, use `python3 tools/freeze_library.py --pin-existing` (add `--config PATH` for a non-default config). This recovery-only mode validates the complete manifest and every source digest with the read-only library validator, recomputes the content-addressed snapshot ID, and stores that ID in config without changing the library tree. It refuses to replace an already-configured, different pin. Normal freezer mode remains non-overwriting; it will not replace an existing manifest.

The reader traverses from directory descriptors, lists entries through opened directories, and opens manifest/source files with `O_NOFOLLOW | O_NONBLOCK`; it checks types, sizes, and identities with `fstat`, verifies the directory entry again after reading, and rechecks the complete tree before returning. Symlink swaps and FIFO replacements therefore fail closed without following the link or waiting for a FIFO writer.

An episode may begin only when those channels match the declared empty state. At episode close, including when a worker raises an exception, the orchestrator wipes these values and checks the same empty-state baseline. If residue remains, it raises `ResetAuditError`; the CLI reports an error instead of returning an answer. Reset and replay audit markers are printed only in explicit `--audit-replay` mode; ordinary CLI output remains status-first.

`Orchestrator.run_episode(task, seed=...)` also captures a pre-reset replay snapshot. Its deterministic-state digest covers the task fingerprint, seed, worker routing sequence, and per-role call counts. Full worker-result fingerprints are kept separately: a changed model response is observable, but is not silently treated as a reset leak. The test suite runs the same task twice with the same seed through deterministic worker doubles, compares both the declared-state digests and the result fingerprints, and does not contact an LLM server. The seed is currently audit metadata; it is not forwarded to or used to seed the external model service, so this test does not claim deterministic real-model output.

The explicit CLI path performs the same two-run equality check:

```sh
./run.sh --audit-replay --seed 713 --model llama3.2:1b 'Repeat this fixed task'
```

`--audit-replay` reports `PASS` only if both reset checks pass and the declared-state snapshots match. A mismatch raises a clear error and returns a non-zero status. The CLI separately reports whether worker-result fingerprints match; a difference is informational and is not described as a reset failure. Without `--audit-replay`, normal CLI invocation still runs exactly one episode. The seed is recorded for audit comparison but is not sent to the model server.

The reset tests also run consecutive episodes through one orchestrator and inject a deliberately broken reset implementation. The clean case checks both episodes start without prior worker context and end at the same empty-state digest. The leak case confirms residue blocks the result and prevents the next episode from invoking workers.

Run only the focused reset tests with:

```sh
python3 -m unittest discover -s tests -p 'test_reset_state.py' -v
```

## Limits

This is an audit of declared in-process state, not a proof of a total system wipe or complete state equality. The current harness does not own or inspect the local model server's KV/cache state, operating-system scheduler, file descriptors, network side channels, scratch filesystem, or temporary embeddings. The OpenAI-compatible model server is an external process and may retain state that this client cannot observe. The episode result returned to the caller is intentionally retained as output. Descriptor-relative reads narrow library path races, but there is no filesystem snapshot or lock: a sufficiently privileged concurrent writer could alter and restore data between checks, so this does not prove integrity against a malicious root-level actor.

The broader Phase 4 brief also names a daily calibration canary, a deployment gate, and a gate covering 100 fresh starts/per-episode cells. This repository has no calibration dataset, canary thresholds, deployment service, or 100-cell runner; none of those gates are implemented or claimed here. The project traceability notes list the equality boundary and canary thresholds as unresolved, so this branch does not invent them.
