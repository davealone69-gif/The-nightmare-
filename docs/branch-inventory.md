# Branch inventory: separate apps

Snapshot taken 2026-11-14 through GitHub branch, recursive tree and commit metadata APIs. This covers all 16 advertised `3d-mega-hub` branches and all 5 advertised `The-nightmare-` branches at inspection time; tree responses were not truncated. The current Arena working branch is local and is not listed by that GitHub branch API. No branch was switched, merged, deleted, or changed in either remote repository. Branches can move after this snapshot.

This is a branch/tree and selected-source review, **not** a full line-by-line audit, build, or device test of every branch. Labels describe files or source intent, not proof of working behavior. Refer to exact SHAs before making decisions.

## 3D Mega Hub

| Branch | Commit | Source inventory summary |
|---|---|---|
| `agent/android-build-check-2026-11-03` | [`b00b79bf9580`](https://github.com/davealone69-gif/3d-mega-hub/tree/b00b79bf958064fac2d8b8f3a1fe451a8c3d6c5a) | Android shell/build-check line |
| `docs/glb-display-generation-baseline` | [`12b5db2e0eaf`](https://github.com/davealone69-gif/3d-mega-hub/tree/12b5db2e0eafba6847a971bf1a0238b504dda9f8) | Shell plus GLB baseline documentation |
| `feature/first-working-gles-scene` | [`31ea0b04c08c`](https://github.com/davealone69-gif/3d-mega-hub/tree/31ea0b04c08cc96a612634ab1be238909f233526) | Interactive GLES scene source |
| `fix/filament-engine-jni-init` | [`59743288c839`](https://github.com/davealone69-gif/3d-mega-hub/tree/59743288c839ab10a323a9595d1f97f37ef841c8) | Filament preview/JNI and R8 fix source |
| `fix/mega-hub-candidate-hardening` | [`41c3069301dc`](https://github.com/davealone69-gif/3d-mega-hub/tree/41c3069301dc772bad3aa8dba1a2cbf67f8110c9) | Engine integration candidate and hardening source |
| `integration/avatarforge-renderer-port` | [`a42411302b7f`](https://github.com/davealone69-gif/3d-mega-hub/tree/a42411302b7f9e9eabe171f844d8b4d646f1c23b) | Integration line: AvatarForge native engine and renderer, Filament preview, GLB diagnostics |
| `main` | [`aaee4443dbf5`](https://github.com/davealone69-gif/3d-mega-hub/tree/aaee4443dbf53ef4707cce01d99f71a59e903ec7) | Android shell and capability status |
| `test/photo-realistic-avatar-overlay` | [`705f3571311e`](https://github.com/davealone69-gif/3d-mega-hub/tree/705f3571311ebcc63bc7b7b2a3c9d9953ff341c0) | Integration-related tree plus photo-overlay quality backlog |
| `trae/agent-hDYcEF` | [`6ca717fcffeb`](https://github.com/davealone69-gif/3d-mega-hub/tree/6ca717fcffeb3b7db117fc2fe8b3b5cf9a0101fd) | Filament preview and renderer factory source |
| `work/android-shell-foundation` | [`9975cea1afb0`](https://github.com/davealone69-gif/3d-mega-hub/tree/9975cea1afb0fc08b52859925d63bb2fbdb92de4) | Android shell and CI foundation |
| `work/avatarforge-core-integration` | [`731bd31bf431`](https://github.com/davealone69-gif/3d-mega-hub/tree/731bd31bf431cf391cb052d1c06d49e0c7255ba9) | AvatarForge generation-core integration source |
| `work/engine-integration-candidate` | [`7ba8526850ef`](https://github.com/davealone69-gif/3d-mega-hub/tree/7ba8526850ef85a3288c4984a82775ac08c77a65) | Filament preview and AvatarForge integration candidate |
| `work/filament-gles-renderer` | [`3856bb6ef472`](https://github.com/davealone69-gif/3d-mega-hub/tree/3856bb6ef472e17cbbe54540cd860518e50ab85c) | Filament/GLES preview source |
| `work/filament-renderer-live-diagnostics` | [`2a1352c5994a`](https://github.com/davealone69-gif/3d-mega-hub/tree/2a1352c5994a42a586a929d995a33fcb4f6ebd67) | Filament preview and live diagnostics source |
| `work/fileprovider-renderer-diagnostics` | [`e85b1971d08b`](https://github.com/davealone69-gif/3d-mega-hub/tree/e85b1971d08b2ebd6b9204c51c15038972c948ac) | Renderer diagnostics and FileProvider work |
| `work/samsung-s24-photoreal-patch` | [`6c601ac067a3`](https://github.com/davealone69-gif/3d-mega-hub/tree/6c601ac067a3ba2223f6b617c6ffff412c8198d0) | AvatarForge renderer and S24 patch candidate |

## THE NIGHTMARE

| Branch | Commit | Source inventory summary |
|---|---|---|
| `feature/android-native-conversion` | [`f914b2a2da18`](https://github.com/davealone69-gif/The-nightmare-/tree/f914b2a2da18cbdef4ddc5fae44d2acba60c099f) | Native Android and Python harness |
| `integration/local-phase3-5` | [`efdda3a269a9`](https://github.com/davealone69-gif/The-nightmare-/tree/efdda3a269a99c0c14147c92a7f9c5d2ee17da98) | Python harness, library, reset audit and review |
| `integration/local-phase3-6` | [`5711b31b764e`](https://github.com/davealone69-gif/The-nightmare-/tree/5711b31b764e8fee55f5774bf1bea0a7c8c993a1) | Python harness and portable export |
| `main` | [`f5e2e7499385`](https://github.com/davealone69-gif/The-nightmare-/tree/f5e2e7499385da8428954345db8057311faa7d9d) | Initial commit; one file |
| `phase1/local-agent-harness` | [`5cc04fc57d80`](https://github.com/davealone69-gif/The-nightmare-/tree/5cc04fc57d802e52b16ede486e455d53f9ed4712) | Earlier Python harness |

## Selected findings and evidence limits

- The owner requires **two separate apps**: separate packages, repositories, storage, build and release lifecycles. No app merging or shared installation is proposed. User-directed export/import is only a possible future option, not a present integration or authorization to implement one.
- The Mega Hub integration branch contains `AvatarForgeEngine.kt`, `AvatarForgeSurfaceView.kt`, native renderer and shader source, a bundled GLB, and renderer instrumentation. Its README describes deterministic native prompt generation and local Ollama chat, not trained avatar generation or an embedded model. The tests and code exist; their presence does not prove a particular result.
- At exact integration commit `a42411302b7f9e9eabe171f844d8b4d646f1c23b`, GitHub run [43616973631](https://github.com/davealone69-gif/3d-mega-hub/actions/runs/43616973631) reports Android CI success, while [43616973704](https://github.com/davealone69-gif/3d-mega-hub/actions/runs/43616973704) reports diagnostic failure and [43616973663](https://github.com/davealone69-gif/3d-mega-hub/actions/runs/43616973663) reports generated-avatar runtime verification failure. Job conclusions alone do not identify failure causes; no full log or physical phone test was checked in this inventory.
- The Mega Hub integration README calls `integration/avatarforge-renderer-port` the canonical integration line, but that is a repository-stated status, not a direction to merge or delete other branches. No branch is assumed to contain all other branches.
- THE NIGHTMARE working tree also contains the uncommitted user-facing guidance and roadmap changes from this session. They are not included in the remote branch SHA above.
