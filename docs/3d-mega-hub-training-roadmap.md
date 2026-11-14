# THE NIGHTMARE + 3D Mega Hub: training roadmap

**Status: proposal, not an implemented integration.** The owner describes THE NIGHTMARE's goal as developing AI expertise in 3D rendering, shaders and photorealistic avatar creation for 3D Mega Hub. **The two apps must remain separate:** their repositories, packages, storage, builds, installations and release lifecycles stay independent. Nothing here authorizes merging the apps or implementing direct communication. Any future user-directed transfer requires a separate owner decision. This roadmap does not claim that THE NIGHTMARE can currently train a model or render an avatar.

## Inspected baseline

- [3D Mega Hub, `main` at `aaee4443dbf53ef4707cce01d99f71a59e903ec7`](https://github.com/davealone69-gif/3d-mega-hub/tree/aaee4443dbf53ef4707cce01d99f71a59e903ec7): the Android Compose shell's `MainActivity.kt` creates capability cards with no backend configured. `RuntimeCapability.kt` reports those as **NOT INTEGRATED**. The manifest declares optional GLES 3.0; it does not establish a working renderer. The tree inspected contains no render, asset, training, job, or cross-app API implementation. This is a source inspection, not an exhaustive audit of other branches or a build/device test.
- That observation applies to `main` only. A [branch inventory](branch-inventory.md) found substantial renderer and AvatarForge source on other branches, including `integration/avatarforge-renderer-port` at `a42411302b7f9e9eabe171f844d8b4d646f1c23b`. Its README describes a Filament preview, native AvatarForge renderer and deterministic native generation. This is source and repository documentation evidence, not a claim of photorealism or successful phone operation; the exact branch's diagnostic and generated-avatar runtime CI runs have failed. Branches are distinct and must not be merged on this basis.
- Its `docs/MASTER_BLUEPRINT.md` proposes evaluating Filament on OpenGL ES, with Vulkan optional and renderer acceptance based on real visible pixels on the target phone. The blueprint alone is not a device verification.
- THE NIGHTMARE's Android app currently runs a text-only Retriever → Analyst → Formatter pipeline using a loopback Ollama-compatible endpoint, a frozen text library, and a local human-review queue. Its review screen records externally supplied observations; it does not run the monitors. It cannot inspect 3D scenes, evaluate images, update model weights, or call 3D Mega Hub.
- The existing `implementation-spec.md` describes a narrower proposed synthetic still-image evaluator and safety boundaries. It is not an implementation of this broader training goal. A later approved specification revision should reconcile scope and requirements; this roadmap does not silently replace its safeguards or claim they exist.

**Phone constraint:** the owner's target is on-phone training using example conversations. No supported phone training engine, compatible model, adapter format, or measured memory/thermal budget has been selected. Ollama inference and adding examples to a library are not model fine-tuning. On-phone weight updates are **BLOCKED / UNVERIFIED** until a real backend is chosen and tested. Do not fall back to a remote service without explicit approval.

## Separation boundary and optional future exchange

Android apps have separate private storage; neither app should read the other's private files. No exchange or direct API is implemented or authorized by this roadmap. If the owner separately chooses to transfer material, a user-selected, versioned **offline exchange package** is one possible future design, subject to each independent app implementing and testing its own side. No automatic sharing, hidden background sync, arbitrary code execution or modification of 3D Mega Hub assets is implied.

Proposed package v0 (not yet a contract):

- Manifest: schema version, package ID, creation time, originating application/version/commit when known, task type, declared rights and permitted uses, and SHA-256 for each included file.
- Case: instruction, desired result, human-authored reference or rubric, training/evaluation purpose, and source/asset family ID. Reference a local asset or render only when explicitly included and authorized. Do not include personal photos by default.
- Render context, when available: renderer/backend and version, device/OS, asset ID and digest, camera, lighting, color-management and material settings, plus output image digest. Mark missing values as unknown, never fabricate them.
- Human review: decision, rationale, reviewer label and provenance. Label machine-generated suggestions as machine-generated; a human must explicitly approve an example for training. Rejecting or editing a suggestion is not itself a weight update.
- Training output, when implemented: dataset and split digests, base model/tokenizer/runtime versions, training settings, adapter/model digest, run status, evaluation evidence and explicit human approval. This describes required provenance, not a claim of signed or immutable artifacts today.

Before implementing a format, agree on a schema with 3D Mega Hub, supported size limits, media types, duplicate handling and version compatibility. Validate a package in staging; reject unsafe paths, links, undeclared files, mismatched hashes and unsupported versions. Do not interpret imported text as app instructions or execute imported shaders/scripts. SHA-256 detects changes relative to a trusted manifest; it does not prove authorship or rights. Any later direct Android IPC must separately define authentication, permissions, URI grants and lifecycle behavior.

## Staged work

### 1. Human-curated text examples — first deliverable

In THE NIGHTMARE, add an on-device, private workspace for instruction/ideal-answer pairs focused on rendering, shader reasoning and avatar workflows. The current working branch includes a **partial, Android-build/device-unverified** Train screen with guided prompts and app-private create/edit/delete/review of examples and source/permission notes. Editing resets the review label. It does not include example export/import, a training engine, or automatic inclusion in the frozen library or existing review journals. The collection is **practice and training data preparation only**, with no weight-update button or fake progress.

**Acceptance:** offline tests cover malformed/oversize inputs, edit/delete, approval and revocation, privacy, interrupted writes, and exact export/import round trips. A human can inspect what is exported. Device UI and storage behavior must be tested on the S24. Do not claim the exported format works with an unspecified trainer.

### 2. Optional, separately approved evidence exchange

If the owner wants a transfer between the separate apps, evaluate a small user-directed export of a known scene and render with settings and provenance, based on the selected Mega Hub branch's actual capabilities. THE NIGHTMARE could present it for human review. Only add automated image/scene assessment after choosing real compatible capabilities. Keep scene editing and rendering in 3D Mega Hub; suggestions remain proposals until an operator applies them. This step is not required for the apps to remain independent.

**Acceptance:** independently produced package fixtures validate in both apps; test corrupt, missing, oversized and incompatible data, user cancellation, and private-storage boundaries. On the S24, show a known textured model with non-background pixels and record lifecycle, performance and thermal evidence. Neither app treats a source-code claim as a device pass.

### 3. Real training feasibility and execution

Evaluate an explicitly identified Android/Termux-compatible trainer, base model, license and data format. Measure actual RAM, storage, battery/thermal effects and time on the S24 on a small user-approved batch, including cancellation and recovery. If it cannot fit or complete, report **BLOCKED** and retain data preparation; do not silently transfer data or use an off-device trainer. If the owner later opts into an external trainer, define a separate explicit export and consent path.

**Acceptance:** a reproducible, user-started run produces a verifiable adapter or model artifact; errors and cancellation are real, not simulated. Keep the original model untouched and provide rollback. Model output must be compatible with the selected inference runtime before claiming it can be used. No online learning or automatic promotion.

### 4. Evaluate expertise and promote only after review

Create human-reviewed examples and held-out cases for shader correctness and safety, material and lighting reasoning, scene compatibility, asset handling, and avatar visual quality. Separate related assets, prompts and variants across train/dev/holdout to reduce leakage. Check rendering with real compiled shaders and image/scene evidence where supported, not only a model's self-reported confidence or valid citation IDs. Compare a fixed baseline to the candidate on the same cases, including regressions and inability to answer. Report coverage, per-case outcomes and uncertainty; do not claim expertise from a single example.

A person reviews the evidence and explicitly selects whether to use a candidate; preserve the previous model and evaluation records. Rights, consent, licensing, privacy, and data deletion/withdrawal procedures must be settled before using real-person likenesses or personal photos. The existing specification excludes personal-photo evaluation; any expansion needs an explicit scope and safeguards decision.

## Decisions still needed

1. Which initial capabilities matter most: shader authoring/diagnosis, material and lighting recommendations, asset troubleshooting, or render critique?
2. Which Android-compatible model/trainer and model license are intended? What resources and stopping limits are acceptable on the S24?
3. What examples can the owner provide, and what permissions allow their use for training? What sensitive information must never be exported?
4. When 3D Mega Hub has a renderer and asset interface, which exact version will define the exchange format and fixtures?
5. What evidence and human approval are sufficient before an updated model is used?

Until these decisions and tests are complete, this document is a roadmap, not an assertion of integration, fine-tuning, renderer support, security certification or device readiness.
