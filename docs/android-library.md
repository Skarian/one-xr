# Android Library Guide (`onexr`)

`onexr` is the Android library module that powers this repository.

It exposes a coroutine-first API for:

- session lifecycle
- real-time IMU and magnetometer snapshots
- pose output (`absoluteOrientation` + `relativeOrientation`)
- runtime pose mode selection (`RAW_IMU` / `SMOOTH_IMU`)
- calibration, recenter, and control/config commands

If you only need project overview, use [`README.md`](../README.md). This guide
is the detailed integration reference.

## Requirements

- Android `minSdk 26`
- Android `compileSdk 35`
- Kotlin/Java target `17`
- Network path to the glasses (default link-local host `169.254.2.1`)
- Glasses mode set to `Follow` for calibration/tracking

## Add the library

### Option A: Source module

`settings.gradle.kts`

```kotlin
include(":app", ":onexr")
```

`app/build.gradle.kts`

```kotlin
dependencies {
    implementation(project(":onexr"))
}
```

### Option B: Maven artifact

```kotlin
implementation("io.onexr:onexr:<version>")
```

## Required Android permissions

The library manifest is intentionally empty. Your app must declare:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

`ACCESS_NETWORK_STATE` is required for connection routing and all network-backed
operations (`start`, control commands, config fetch).

## Core types and entrypoint

- `OneXrClient`: main client API
- `OneXrEndpoint`: host/control/stream endpoint config
- `XrSessionState`: connection/calibration/streaming/error state machine
- `XrSensorSnapshot`: latest IMU + MAG values in protocol order
- `XrPoseSnapshot`: orientation output in degrees + bias terms used
- `XrPoseDataMode`: `RAW_IMU` or `SMOOTH_IMU`

Default endpoint:

- host: `169.254.2.1`
- control port: `52999`
- stream port: `52998`

## Minimal integration example

```kotlin
import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.onexr.OneXrClient
import io.onexr.OneXrEndpoint
import io.onexr.XrPoseDataMode
import io.onexr.XrPoseSnapshot
import io.onexr.XrSessionState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TrackingActivity : AppCompatActivity() {
    private lateinit var client: OneXrClient
    private var sessionJob: Job? = null
    private var poseJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = OneXrClient(
            context = applicationContext,
            endpoint = OneXrEndpoint()
        )
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun startTracking() {
        lifecycleScope.launch {
            try {
                client.setPoseDataMode(XrPoseDataMode.RAW_IMU)
                val info = client.start()
                Log.i("xr", "connected iface=${info.interfaceName} ms=${info.connectMs}")
                observeClient()
            } catch (t: Throwable) {
                Log.e("xr", "start failed", t)
            }
        }
    }

    private fun observeClient() {
        sessionJob?.cancel()
        sessionJob = lifecycleScope.launch {
            client.sessionState.collect { state ->
                when (state) {
                    XrSessionState.Idle -> Log.d("xr", "idle")
                    XrSessionState.Connecting -> Log.d("xr", "connecting")
                    is XrSessionState.Calibrating -> Log.d(
                        "xr",
                        "calibrating ${state.calibrationSampleCount}/${state.calibrationTarget}"
                    )
                    is XrSessionState.Streaming -> Log.d("xr", "streaming")
                    is XrSessionState.Error -> Log.e("xr", "session error=${state.code}:${state.message}")
                    XrSessionState.Stopped -> Log.d("xr", "stopped")
                }
            }
        }

        poseJob?.cancel()
        poseJob = lifecycleScope.launch {
            client.poseData.collect { pose ->
                if (pose != null) renderPose(pose)
            }
        }
    }

    fun zeroView() {
        lifecycleScope.launch { client.zeroView() }
    }

    fun recalibrate() {
        lifecycleScope.launch { client.recalibrate() }
    }

    fun stopTracking() {
        sessionJob?.cancel()
        poseJob?.cancel()
        sessionJob = null
        poseJob = null
        lifecycleScope.launch { client.stop() }
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun renderPose(pose: XrPoseSnapshot) {
        val relative = pose.relativeOrientation
        Log.d(
            "xr",
            "relative pitch=${relative.pitch} yaw=${relative.yaw} roll=${relative.roll}"
        )
    }
}
```

## Lifecycle semantics

`start()`:

- establishes stream transport
- loads and validates config for tracker bias activation
- enters calibration
- returns successfully only after first valid report is parsed

`stop()`:

- cancels stream and control sessions
- resets runtime state
- sets `sessionState` to `Stopped`

`isXrConnected()` returns `true` only for `Calibrating` or `Streaming` states.

## Pose output and smoothing mode

`poseData` emits `XrPoseSnapshot` values:

- `absoluteOrientation`: world-frame tracker output (raw estimator output)
- `relativeOrientation`: recentered orientation after `zeroView`

`XrPoseDataMode` controls publishing behavior:

- `RAW_IMU`: no additional smoothing
- `SMOOTH_IMU`: smooths `relativeOrientation` only

`absoluteOrientation` is not smoothed in either mode.

### Switching mode at runtime

```kotlin
client.setPoseDataMode(XrPoseDataMode.SMOOTH_IMU)
```

- mode can be changed while streaming
- default mode on a new `OneXrClient` is `RAW_IMU`
- `sensorData` remains raw regardless of pose mode

## Calibration, recentering, and expected operator flow

Recommended run sequence:

1. `start()` with glasses still
2. wait until `sessionState` reports calibration completion/streaming
3. call `zeroView()` facing neutral forward direction
4. use `recalibrate()` if the user needs a fresh stillness calibration

Behavior notes:

- `zeroView()` recenters `relativeOrientation`; it does not modify
  `absoluteOrientation`
- `recalibrate()` restarts calibration progression in the stream runtime

## Reading raw sensor snapshots

`XrSensorSnapshot` carries latest known IMU and MAG samples plus metadata.
Vectors stay in protocol field order.

```kotlin
lifecycleScope.launch {
    client.sensorData.collect { snapshot ->
        if (snapshot == null) return@collect
        snapshot.imu?.let { imu ->
            Log.d(
                "xr",
                "gyro=[${imu.gx}, ${imu.gy}, ${imu.gz}] accel=[${imu.ax}, ${imu.ay}, ${imu.az}]"
            )
        }
        snapshot.magnetometer?.let { mag ->
            Log.d("xr", "mag=[${mag.mx}, ${mag.my}, ${mag.mz}]")
        }
    }
}
```

## Bias state and correction model

`biasState` is a `StateFlow<XrBiasState>` and is observable during startup and
runtime:

- `Inactive`
- `LoadingConfig`
- `Active(fsn, glassesVersion)`
- `Error(code, message)`

The runtime correction model is:

- `gyro_corrected = gyro_raw - factory_temp_interpolated_bias - runtime_residual_bias`
- `accel_corrected = accel_raw - factory_accel_bias`

If bias prerequisites fail, startup fails fast and `sessionState` transitions to
`Error`.

## Controls and configuration APIs

`OneXrClient` also exposes control/config calls:

- scene mode: `setSceneMode(...)`
- display input mode: `setDisplayInputMode(...)`
- brightness and dimmer: `setBrightness(...)`, `setDimmer(...)`
- identity/version reads: `getId()`, `getSoftwareVersion()`, `getDspVersion()`
- config reads: `getConfigRaw()`, `getConfig()`

These calls can be used from a control-only flow without calling `start()`.
`getConfig()` accepts any integer `glasses_version`; versions `7` and `8` are
currently validated in-house, and other values emit a warning but do not fail
parsing.

```kotlin
import android.Manifest
import androidx.annotation.RequiresPermission
import io.onexr.OneXrClient
import io.onexr.XrControlProtocolException
import io.onexr.XrDeviceConfigErrorCode
import io.onexr.XrDeviceConfigException
import io.onexr.XrDimmerLevel
import io.onexr.XrDisplayInputMode
import io.onexr.XrSceneMode

@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
suspend fun applyDisplaySettings(client: OneXrClient) {
    client.setSceneMode(XrSceneMode.ButtonsEnabled)
    client.setDisplayInputMode(XrDisplayInputMode.Regular)
    client.setBrightness(5)
    client.setDimmer(XrDimmerLevel.Middle)
}

@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
suspend fun readConfig(client: OneXrClient): String {
    return try {
        val config = client.getConfig()
        "config ok fsn=${config.fsn} gyroBiasSamples=${config.imu.gyroBiasTemperatureData.size}"
    } catch (e: XrDeviceConfigException) {
        when (e.code) {
            XrDeviceConfigErrorCode.PARSE_ERROR -> "config parse_error"
            XrDeviceConfigErrorCode.SCHEMA_VALIDATION_ERROR -> "config schema_validation_error"
        }
    } catch (e: XrControlProtocolException) {
        "control transport_error"
    }
}
```

## Advanced streams (`client.advanced`)

`OneXrAdvancedApi` exposes additional flows:

- `diagnostics: StateFlow<HeadTrackingStreamDiagnostics?>`
- `reports: SharedFlow<OneXrReportMessage>`
- `controlEvents: SharedFlow<XrControlEvent>`

These are useful for telemetry, debugging, and validating parser/control behavior.

## Troubleshooting

`No matching Android Network candidate for host 169.254.2.1`

- verify `ACCESS_NETWORK_STATE` is granted
- call `describeRouting()` to inspect interface/network candidates
- confirm the phone has a route to the glasses link-local host

`Timed out waiting for first valid report during startup`

- verify stream/control ports (defaults: `52998`/`52999`)
- confirm glasses mode is `Follow`
- inspect `client.advanced.diagnostics`

`sessionState = Error(...)`

- inspect `code`, `message`, `causeType`
- call `stop()` and retry `start()`

`biasState = Error(...)`

- run `getConfigRaw()` and `getConfig()` to inspect payload quality
- handle `XrDeviceConfigException` (`PARSE_ERROR`, `SCHEMA_VALIDATION_ERROR`)

## Demo app references

- Sensor integration reference:
  `app/src/main/java/io/onexr/demo/SensorDemoActivity.kt`
- Control integration reference:
  `app/src/main/java/io/onexr/demo/ControlsDemoActivity.kt`

## Migration note

If you are updating from earlier naming, update imports and types to current
`io.onexr` / `OneXr*` symbols.
