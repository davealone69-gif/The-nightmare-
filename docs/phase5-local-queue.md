# Phase 5 local monitor and human-review queue

This is a local CLI implementation of the Phase 5 queue boundary. It does not run a monitor detector or authenticate a real person; the human reviewer ID is an explicit operator-supplied label. Do not use it as a production access-control or cryptographic audit system.

## Data location

Queue data lives under `<data_dir>/phase5-review/`, where `data_dir` is the validated external path in `config.json`. The CLI refuses to place it inside the source tree. Override it per command with `--data-dir /absolute/external/path`. Files are:

- `streams/mandela.jsonl` — semantic-monitor findings.
- `streams/matrix.jsonl` — mechanism-monitor findings.
- `human-decisions.jsonl` — human approve/reject/edit decisions, appended separately.
- `next-episodes.jsonl` — immutable prepared next-episode context records.

Each monitor writer opens only its own file with the OS `O_WRONLY | O_APPEND` flags; the writer API has no read method. The two monitor files are separate capabilities. The human review command is the only CLI path that reads both to build a queue, and it displays both streams and their provenance openly. This is a local process/API boundary, not an OS sandbox: a process running as the same account with filesystem access can still inspect or alter the data files. Back up and protect the external directory accordingly.

## Append a machine observation

Use the explicit `--phase5 monitor observe` command only to record a detector's structured finding. It does not create a correction or contact a worker. The prefix keeps task text that happens to begin with `review` or `monitor` from being treated as an admin command.

```sh
./run.sh --phase5 monitor observe \
  --stream mandela \
  --episode-id episode-001 \
  --signal-code CONSENSUS_DRIFT \
  --severity P2 \
  --confidence 0.81 \
  --coverage 0.94 \
  --component-version mandela-local-1 \
  --summary 'Workers repeated an unsupported shared claim' \
  --evidence-ref event:episode-001:11
```

Choose `--stream mandela` or `--stream matrix`. Each resulting record explicitly says `authorship: machine`, includes the component/version, signal code, evidence references, confidence and coverage, and is appended rather than replacing prior records. The command acknowledges the record as a machine-authored signal pending human review.

## Human review

List pending items with `./run.sh --phase5 review queue`, or include prior decisions with `--include-reviewed`. Each item states which monitor produced it, its version and signal, and that the human decision is separate. `--phase5 review show <observation-id>` shows the full provenance before a decision. This CLI is not a blinded first-pass interface.

```sh
./run.sh --phase5 review queue
./run.sh --phase5 review show <observation-id>
./run.sh --phase5 review approve <observation-id> --reviewer-id 'anchor-name'
./run.sh --phase5 review reject <observation-id> --reviewer-id 'anchor-name' --reason 'Evidence did not support the signal'
./run.sh --phase5 review edit <observation-id> --reviewer-id 'anchor-name' --edit-text 'Human-authored guidance for a later episode'
```

A rejection requires a human-authored reason; an edit requires human-authored replacement text. One observation can receive one decision. A second attempt is rejected. Decisions are appended as human-authored events and retain the source observation ID, stream, component/version, signal code, and machine-finding digest. The CLI does not label a machine finding as written by the human.

## Prepare and run a later episode

After review, prepare context for the next episode, then pass both the source and next episode ID to the normal command:

```sh
./run.sh --phase5 review next --source-episode episode-001 --next-episode episode-002
./run.sh --episode-id episode-002 --next-episode-from episode-001 'Review this new task independently'
```

Preparation is blocked while any monitor finding from the source episode is still pending review. After every finding has an explicit decision, only approved findings and explicitly human-authored edits are included; rejected items are excluded. An approved machine finding remains labelled machine-authored and includes the human approver's ID. An edit includes both the original machine finding and the separate human-authored text. The generated context tells workers it is human-reviewed structured data, not policy. Preparing the same next episode from the same unchanged source review is idempotent; new findings or decisions require a new next-episode ID. This feature does not modify the library, train a model, or automatically issue a correction.

## Operational limits

- Observations and human decisions are separate append-only JSONL records, but this local implementation does not add a cryptographic hash chain, digital signatures, file locking across an adversarial filesystem, reviewer authentication, anchor rotation, a sealed evidence store, or dual-control access.
- Data is outside the code tree but the local operating-system account can access it. Stream isolation is enforced by the writer's one-file `O_WRONLY|O_APPEND` descriptor and API surface, not separate OS users or processes.
- Detector output is human-visible; no attempt is made to hide monitoring, monitor identity, or machine provenance.
- The local CLI has no network API, worker-correction path, automated remediation, visible critic pair, reset audit, or retraining path.
- Next-episode inputs are ordinary task context; worker instructions and application policy remain unchanged. They are not a verified training signal or certification result.
- Every JSONL journal is capped at 8 MiB; reads reject larger existing files before loading content, and appends stop before the cap. There is no automatic rotation or deletion. After resolving the current queue and preparing any required next-episode context, preserve the whole external `phase5-review` directory and start a fresh external `--data-dir` for new observations/decisions. Keep the prior directory available and pass `--review-data-dir` when a run still needs to read its prepared context.
