# Output Contract v0.3

**Status:** Implemented on branch `fix/nightmare-contract-v0.3`. Runtime tests are not yet executed.

## Identity fields

Every `Output` contains:

- `component_hash: str`: lowercase SHA-256 hex digest of the actual component artifact.
- `component_kind: Literal["model", "code"]`: identifies what the hash pins.

Rules:

- Worker and visible-critic outputs use `component_kind="model"`; `component_hash` is the approved model weights/artifact digest.
- Ghost outputs use `component_kind="code"`; `component_hash` is the ghost implementation artifact digest.
- A `ghost.*` role with `component_kind="model"` is invalid.
- A non-ghost role with `component_kind="code"` is invalid.
- Model hashes are checked against the approved model hash set. Code hashes are checked against a separate approved code-component hash set through `Output.validate(approved_code_components=...)`.
- Missing, malformed, or incorrectly typed component identity fails validation.
- The ambiguous `weights_hash` field is removed from `Output`. Reset-plan `expected_weights_hash` remains a separate model-server reset pin.

## Compatibility and sequencing

This is a breaking schema revision from v0.2. Producers, consumers, tests, and sealed output readers must migrate together. The grading engine must not be built against the older envelope.

The code hash is a canonical source-tree content manifest over a fixed sorted list of relative source paths. Each source file is read as UTF-8, line endings are normalized to LF, and the normalized bytes are SHA-256 hashed. The sorted manifest contains only relative paths and per-file content hashes, then is itself canonical-JSON hashed. Absolute paths, checkout roots, timestamps and filesystem metadata are excluded. The current ghost manifest pins `contracts.py` and `ghosts.py`; changing either changes the ghost artifact hash. Runtime deployment must still pin the resulting hash in an approved code-component manifest.

## Verification

The contract and call sites are committed on this branch. Unit tests have not yet been executed. Do not label the schema runtime-verified until the complete test suite passes.
