# THE NIGHTMARE for Android

This is a **private, local-only Android client for personal training**. It is not being prepared for public release. The new native Kotlin app is additive: the Python standard-library Termux CLI remains intact, and this work does not modify or relocate its user data. The app uses platform Android views and libraries only. Model requests go directly to an OpenAI-compatible Ollama server on loopback; there is no cloud fallback. A deny-by-default Android network-security configuration permits cleartext HTTP only to exact loopback names/addresses. The app validates those exact hosts without DNS lookups and disables HTTP redirects and proxies. Android backup is disabled, and settings, imported library copies, and review journals remain in the app's private storage.

The app can run tasks through Retriever → Analyst → Formatter, classify citation IDs, abstain on live-data or unsupported source requests, freeze or import a verified library, recover or explicitly change its snapshot pin, and review monitor observations with separately recorded human decisions. It can also record a structured observation supplied by an external monitor, but the app does not run a detector or authenticate that claim. Confidence remains uncalibrated. Review decisions and prepared context are not a certification or production audit system. Replay compares the app's declared task/worker state only; it cannot inspect Ollama internals or Android OS state. The app does not run the image-evaluation detector or the broader system in the long-term specification.

## Build a private debug APK

From this directory, run:

```sh
./gradlew assembleDebug
```

The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Debug builds use standard Android debug signing and require no release key or release credentials. This project provides no release-signing or public-distribution setup.

The standalone Android build compiles and targets API 37 (Android 17), with minimum API 26, using AGP 9.5 and its built-in Kotlin support. It is intended for the Samsung Galaxy S24 SM-S921B, but that phone has not been used for an install-and-exercise check; real-device behavior remains **UNVERIFIED**.

## Connect to local Ollama

Start Ollama in Termux on the same phone and set the app's model identifier to an installed model. The default base URL is `http://127.0.0.1:11434/v1`. Keep it unless the local service uses another allowed loopback host, port, or path. The Android app refuses remote hostnames, remote IP addresses, and other loopback aliases outside its explicit allowlist.

## Import Termux settings

On the Settings screen, choose **Import Termux config.json** and select the file through Android's document picker. After reviewing the preview, confirm to fill the form; the values are not stored until you tap **Save settings**. The app imports the loopback URL, model name, timeout, and optional library snapshot pin. It ignores `data_dir` completely and never opens or modifies Termux data, library, or configuration files; your Android reviewer label is preserved.

## Freeze, import, and repin a library

On the Library screen, choose **Freeze or import a library folder** and select a local on-device folder. The folder may contain new `.md`/`.txt` sources or an existing frozen snapshot. The app copies and validates the selection in private staging, checks the manifest and declared file set, hashes, snapshot ID, UTF-8 content, read-only files/directories, and size/count limits, then shows the candidate pin and source count before asking you to install it. It never writes to the selected folder or to Termux's private storage; cloud/third-party document providers are not read. Android rejects observed symlinks and uses no-follow final-file opens, but its public APIs do not expose the descriptor-relative `openat` walk used by Python; pathname traversal is not race-proof against concurrent same-app mutation.

If an Android library already exists, replacing it requires a separate **Replace** confirmation. If the saved or verified existing snapshot pin differs, the button says **Repin**; the old Android copy is retained until the new copy verifies and the settings pin saves. A failed settings save attempts to restore the old copy and pin, and reports when rollback cannot be proven. For an existing unpinned Android library, **Validate and adopt existing library pin** recomputes the full snapshot before offering **Adopt** or **Repin**. Validation itself does not change library files. The configured pin is an integrity anchor, not a cryptographic signature.

When there is no app-private library, **Create app-private demo library** creates the same single sample used by the Termux demo seeder, computes and verifies its frozen manifest, pins it in Android settings, and refuses to replace an existing copy. The sample is only for local retrieval testing, not horticultural advice.

## Portable project export

Choose **Create verified portable export** and select the project's on-device folder. The app scans locally; excludes tests, build/cache/export output, obvious secret paths, and the project `data_dir` when that setting is a safely resolvable relative path; and aborts on recognizable secret-like content. An ambiguous absolute or root-wide `data_dir` is rejected rather than risk bundling runtime data. Exclusions and secret detection are heuristic, not a guarantee. Android SAF cannot prove physical containment if the provider resolves a link outside the selected folder. The scan is also not an atomic filesystem snapshot: the source is checked again, but a change after its last check can still escape detection.

The output is app-private and contains the portable folder, a reproducible ZIP, and a completion manifest binding their hashes. The pair is not atomically visible; interrupted partial exports are kept without a completion manifest and cannot be shared. **Share ZIP + manifest** opens Android's share sheet only after the pair re-verifies, and the provider grants read-only access to just those two files. Choosing a destination sends the selected source material to that recipient; exported copies and storage durability are under the receiving provider's control. Nothing is uploaded by the export itself.

## Copy existing Termux data without moving it

Android app storage is separately sandboxed. The app never reads, moves, overwrites, or deletes Termux's private files directly. To make selected existing files visible in Android's system file picker, open the Termux repository directory on the phone and run `termux-setup-storage` once if shared storage has not already been enabled. Then run this from the repository root; it copies only the frozen library and compatible review JSONL files, refuses to overwrite a previous export folder, and prints the snapshot pin to enter in Android Settings:

```sh
python3 - <<'PY'
import json
import shutil
from pathlib import Path

project = Path.cwd()
config = json.loads((project / "config.json").read_text(encoding="utf-8"))
data_dir = Path(config["data_dir"]).expanduser()
if not data_dir.is_absolute():
    data_dir = (project / data_dir).resolve()
else:
    data_dir = data_dir.resolve()

destination = Path.home() / "storage/shared/Download/the-nightmare-android-import"
if destination.exists():
    raise SystemExit(f"Refusing to overwrite existing folder: {destination}")
library = data_dir / "library"
if not library.is_dir():
    raise SystemExit(f"Frozen library not found: {library}")
destination.mkdir(parents=True)
shutil.copytree(library, destination / "library")
for relative in (
    "phase5-review/streams/mandela.jsonl",
    "phase5-review/streams/matrix.jsonl",
    "phase5-review/human-decisions.jsonl",
):
    source = data_dir / relative
    if source.is_file():
        target = destination / Path(relative).name
        shutil.copy2(source, target)
        print(f"Copied {source.name}")

print(f"Library folder for Android: {destination / 'library'}")
print(f"Snapshot pin for Android: {config.get('library_snapshot_id', '') or '(not configured; inspect config.json after pinning the library)'}")
print("Original Termux files were not changed.")
PY
```

Import `config.json` on the Android Settings screen, or enter the current `library_snapshot_id` manually. If the Termux config has no pin (for example, an older setup), select the copied library folder; the app validates it and presents the verified candidate pin for confirmation. Or, if an unpinned library is already app-private, use **Validate and adopt existing library pin**. A changed pin is never saved silently. Both routes create or validate an Android-app pin only and do not alter the Termux config or original library.

The Review screen can append a structured record supplied by an external Mandela or Matrix monitor. This is operator-entered event recording only: it does not detect a signal, verify its source, or prove that a machine authored it. The saved record is marked machine-attributed and remains subject to explicit human review. To migrate review history instead, import the copied `mandela.jsonl` and `matrix.jsonl` files from the Review screen first, then import `human-decisions.jsonl`. Human decisions are accepted only when they bind to already-imported monitor observations and their digests match. Reimporting duplicate IDs is refused. Existing `next-episodes.jsonl` events are not imported; reviewed context is recomputed from the imported observations and decisions. The original Termux journals remain unchanged.

## Current device failure (2026-11-14)

<badge>BLOCKED</badge> The owner reports that the installed app closes immediately on launch on the Samsung Galaxy S24 SM-S921B. Android displays: “THE NIGHTMARE closed because this app has a bug. Try clearing the app's cache first.” Clearing cache has not been confirmed to resolve the failure. This is a real device failure report, not a reproduced or diagnosed stack trace.

Affected branch: `feature/android-native-conversion`
Affected commit: `7a3472db385c09f56592e32d98d60845ccf3bd85`

The reported API 37 debug build and JVM tests do not establish startup/runtime correctness. Do not mark the Android app fixed or release-ready. Root cause is **UNVERIFIED**; installed-app startup is **BLOCKED** pending a symbolicated crash trace or equivalent device bug report and a successful retest. No speculative code change is represented as a fix.

## Verification status

The Python regression suite and Android local JVM tests run separately. Building the APK does not establish behavior on a physical phone; the Samsung SM-S921B install-and-exercise check is still required and is currently unverified. Android JVM tests cover configuration parsing, frozen-library validation and install rollback, local Ollama HTTP/replay behavior, review provenance, demo-library setup, and portable export integrity.

## Android SAF security boundary

Android's Storage Access Framework does not expose the descriptor-relative `O_NOFOLLOW`/`openat` primitive used by the Python exporter. The native app accepts only the built-in on-device external-storage provider, requires opened file descriptors to be regular files, bounds reads, rechecks descriptor and provider metadata, and rereads content before export. Those checks detect many changes but cannot prove physical containment: a provider may resolve a link before returning the descriptor, and SAF exposes no portable way for the app to verify that the opened inode lies beneath the selected tree. Android's app-private library validator also uses pathname traversal and is not race-proof against a concurrent same-UID mutation. The share provider exposes only app-private verified outputs; other apps cannot normally mutate that private storage, but atomic path-to-descriptor binding is not claimed. Use only trusted local folders, treat the secret scan as heuristic, and review an archive before sharing. No broad all-files access is requested. These Android boundaries are **PARTIAL parity** until hardened or verified on the target Android device and provider behavior.
