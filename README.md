# one-xr

`one-xr` is an Android-first tracking library and demo app for **XREAL One** and
**XREAL One Pro**.

It gives you direct access to live IMU and magnetometer data, plus stable
pose output (`absoluteOrientation` + `relativeOrientation`) without needing the
Unity-based XREAL SDK.

## What is in this repo

- `onexr/`: reusable Android library (`io.onexr`)
- `app/`: demo app (`io.onexr.demo`) with:
  - Sensor Demo (streaming, pose view, calibration, `RAW_IMU` / `SMOOTH_IMU`)
  - Controls Demo (scene/input/brightness/dimmer/config/control events)
- `references/`: upstream references and reproducibility assets

## Quick start (demo app)

From repository root:

```bash
./gradlew :onexr:testDebugUnitTest :onexr:lintDebug :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.onexr.demo/.HomeActivity
```

## Demo usage flow

1. Connect the glasses and set device mode to `Follow` (stabilization off).
2. Open **Sensor Demo** and keep default host/ports unless your setup differs.
3. Tap `Start` and keep glasses still until calibration completes.
4. Tap `Zero View` while facing your neutral forward direction.
5. Choose `PoseData Mode`:
   - `Raw IMU`: unsmoothed baseline pose output
   - `Smooth IMU`: smoother `relativeOrientation` camera path
6. Use `Recalibrate` if drift appears during a run.

Use **Controls Demo** for `getId`, version/config reads, scene/input mode,
brightness, dimmer, and incoming key events.

## Library integration

The detailed integration guide lives at:

- [`docs/android-library.md`](docs/android-library.md)

Start there for lifecycle, flows, permissions, controls/config APIs,
troubleshooting, and code examples.

At a glance:

- Entry point: `io.onexr.OneXrClient`
- Maven coordinates: `io.onexr:onexr:<version>`
- Core outputs: `sessionState`, `sensorData`, `poseData`, `biasState`
- Pose mode control: `poseDataMode` + `setPoseDataMode(...)`
- Runtime controls: `zeroView()`, `recalibrate()`, control/config methods

## Practical implementation notes

- Networking uses Android `Network.socketFactory` for reliable link-local routing.
- Stream parsing uses `magic + big-endian length` framing and dual-header
  compatibility (`2836`, `2736`).
- `sensorData` preserves protocol-order vectors.
- `poseData` publishes physical orientation in degrees:
  - `absoluteOrientation`: world-frame estimator output
  - `relativeOrientation`: recentered output for camera/view control
- `SMOOTH_IMU` smooths `relativeOrientation` only. `absoluteOrientation` remains
  raw.
- `start()` succeeds only after the first valid report is parsed.

## References and provenance

This project is inspired by and cross-checked against:

- [`One-Pro-IMU-Retriever-Demo`](https://github.com/SamiMitwalli/One-Pro-IMU-Retriever-Demo)
- [`xreal_one_driver`](https://github.com/rohitsangwan01/xreal_one_driver)
- [`xr-tools`](https://github.com/justinjoy/xr-tools)

`references/One-Pro-IMU-Retriever-Demo` is included as a submodule with a
small local compatibility patch for reproducibility.

## Run patched reference demo (desktop)

From repository root:

```bash
git submodule update --init --recursive
./references/scripts/apply-reference-patches.sh
./references/scripts/check-reference-patches.sh

cd references/One-Pro-IMU-Retriever-Demo
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python launcher.py
```

`launcher.py` options:

- `1` console mode
- `2` 3D mode

To keep the submodule clean, run from a temp clone:

```bash
tmp_dir="/tmp/one-pro-demo-$(date -u +%Y%m%dT%H%M%SZ)"
git clone references/One-Pro-IMU-Retriever-Demo "$tmp_dir"
git -C "$tmp_dir" checkout 16f45c73610b04b4da238895b46733794a9f5944
patch_file="$(ls references/patches/*/0001-imu-reader-parser-compatibility.patch)"
git -C "$tmp_dir" apply "$PWD/$patch_file"

python3 -m venv "$tmp_dir/.venv"
source "$tmp_dir/.venv/bin/activate"
pip install -r "$tmp_dir/requirements.txt"

cd "$tmp_dir"
python launcher.py
```
