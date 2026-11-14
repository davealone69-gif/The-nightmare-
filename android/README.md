# THE NIGHTMARE for Android

This is a **private, local-only Android client for personal training**. It is not being prepared for public release. The new native Kotlin app is additive: the Python standard-library Termux CLI remains intact, and this work does not modify or relocate its user data. The app uses platform Android views and libraries only. Model requests go directly to an OpenAI-compatible Ollama server on loopback; there is no cloud fallback. A deny-by-default Android network-security configuration permits cleartext HTTP only to exact loopback names/addresses. The app validates those exact hosts without DNS lookups and disables HTTP redirects and proxies. Android backup is disabled, and settings, imported library copies, and review journals remain in the app's private storage.

The app can run tasks through Retriever → Analyst → Formatter, classify citation IDs, abstain on live-data or unsupported source requests, verify an imported frozen library, and review existing monitor observations with separately recorded human decisions. Confidence remains uncalibrated. Review decisions and prepared context are not a certification or production audit system. Replay compares the app's declared task/worker state only; it cannot inspect Ollama internals or Android OS state. The app does not run the image-evaluation detector or the broader system in the long-term specification.

## Build a private debug APK

From this directory, run:

```sh
./gradlew assembleDebug
```

The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Debug builds use standard Android debug signing and require no release key or release credentials. This project provides no release-signing or public-distribution setup.

The standalone Android build compiles and targets API 37 (Android 17), with minimum API 26, using AGP 9.5 and its built-in Kotlin support. It is intended for the Samsung Galaxy S24 SM-S921B, but that phone has not been used for an install-and-exercise check; real-device behavior remains **UNVERIFIED**.

## Connect to local Ollama

Start Ollama in Termux on the same phone and set the app's model identifier to an installed model. The default base URL is `http://127.0.0.1:11434/v1`. Keep it unless the local service uses another allowed loopback host, port, or path. The Android app refuses remote hostnames, remote IP addresses, and other loopback aliases outside its explicit allowlist.

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

In Android Settings, enter the existing `library_snapshot_id` as the pin. If the Termux config has no pin (for example, an older setup), inspect `library/manifest.json` and use its `snapshot_id` only after you trust and verify that frozen library; this sets an Android-app pin only and does not alter the Termux config. On the Library screen, select the copied `library` folder. The importer verifies the manifest, source hashes, computed snapshot ID, size limits, and configured pin before installing a separate app-private copy. It does not change the Termux library.

To migrate review history, import the copied `mandela.jsonl` and `matrix.jsonl` files from the Review screen first, then import `human-decisions.jsonl`. Human decisions are accepted only when they bind to already-imported monitor observations and their digests match. Reimporting duplicate IDs is refused. Existing `next-episodes.jsonl` events are not imported; reviewed context is recomputed from the imported observations and decisions. The original Termux journals remain unchanged.

## Verification status

The Python regression suite and Android local JVM tests run separately. Building the APK does not establish behavior on a physical phone; the Samsung SM-S921B install-and-exercise check is still required and is currently unverified.
