package io.onepro.xr

import java.time.LocalDateTime

/** Error categories emitted while loading typed device config from control JSON. */
enum class XrDeviceConfigErrorCode {
    PARSE_ERROR,
    SCHEMA_VALIDATION_ERROR
}

/** Exception raised when raw config payload cannot be parsed or validated. */
class XrDeviceConfigException(
    val code: XrDeviceConfigErrorCode,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/** Full typed device config parsed from `getConfigRaw()` payloads. */
data class XrDeviceConfig(
    val glassesVersion: Int,
    val fsn: String,
    val lastModifiedTime: LocalDateTime,
    val display: XrDisplaysConfig,
    val displayDistortion: XrDisplaysDistortion,
    val rgbCamera: XrCameraIntrinsicsRadial?,
    val slamCamera: XrSlamCamera?,
    val imu: XrImuDevice,
    val rawJson: String
)

/** Display calibration envelope including both eye surfaces. */
data class XrDisplaysConfig(
    val resolution: XrVector2d,
    val left: XrDisplayConfig,
    val right: XrDisplayConfig
)

/** Per-eye display intrinsics and transform in IMU coordinates. */
data class XrDisplayConfig(
    val intrinsicsMatrix3x3: List<Double>,
    val transform: XrRigidTransform
) {
    init {
        require(intrinsicsMatrix3x3.size == 9) { "intrinsicsMatrix3x3 must contain 9 values" }
    }
}

/** Distortion maps for both displays. */
data class XrDisplaysDistortion(
    val leftDisplay: XrDisplayDistortion,
    val rightDisplay: XrDisplayDistortion
)

/** Distortion grid represented as `numRows * numCols` points. */
data class XrDisplayDistortion(
    val numRows: Int,
    val numCols: Int,
    val points: List<XrDistortionPoint>
) {
    init {
        require(numRows > 0) { "numRows must be > 0" }
        require(numCols > 0) { "numCols must be > 0" }
        require(points.size == numRows * numCols) {
            "points must contain exactly numRows*numCols elements"
        }
    }
}

/** One distortion sample mapping source UV to corrected XY. */
data class XrDistortionPoint(
    val u: Double,
    val v: Double,
    val x: Double,
    val y: Double
)

/** Radial camera intrinsics used by RGB/SLAM camera blocks. */
data class XrCameraIntrinsicsRadial(
    val principalPoint: XrVector2d,
    val focalLength: XrVector2d,
    val distortion: XrDistortionCoefficients,
    val resolution: XrVector2d,
    val rollingShutterTimeSeconds: Double
)

/** SLAM camera calibration including transform and radial intrinsics. */
data class XrSlamCamera(
    val cameraTransform: XrRigidTransform,
    val intrinsics: XrCameraIntrinsicsRadial
)

/** Radial distortion coefficients `[k1, k2, p1, p2, k3]`. */
data class XrDistortionCoefficients(
    val k1: Double,
    val k2: Double,
    val p1: Double,
    val p2: Double,
    val k3: Double
)

/** IMU calibration and bias model used by tracker flows. */
data class XrImuDevice(
    val accelBias: XrVector3d,
    val biasTemperatureCelsius: Double,
    val gyroBias: XrVector3d,
    val gyroBiasTemperatureData: List<XrGyroBiasSample>,
    val magnetometerTransform: XrRigidTransform,
    val intrinsics: XrImuIntrinsics,
    val noises: List<Double>
) {
    init {
        require(gyroBiasTemperatureData.isNotEmpty()) {
            "gyroBiasTemperatureData must not be empty"
        }
        require(noises.size == 4) { "noises must contain 4 values" }
    }

    /** Interpolates factory gyro bias for a target temperature using adjacent samples. */
    fun interpolateGyroBias(temperatureCelsius: Double): XrVector3d {
        val insertionIndex = gyroBiasTemperatureData.indexOfFirst { temperatureCelsius < it.temperatureCelsius }
            .let { if (it == -1) gyroBiasTemperatureData.size else it }

        if (insertionIndex == 0) {
            return gyroBiasTemperatureData.first().bias
        }
        if (insertionIndex == gyroBiasTemperatureData.size) {
            return gyroBiasTemperatureData.last().bias
        }

        val previous = gyroBiasTemperatureData[insertionIndex - 1]
        val next = gyroBiasTemperatureData[insertionIndex]
        val fraction = (temperatureCelsius - previous.temperatureCelsius) /
            (next.temperatureCelsius - previous.temperatureCelsius)
        val keep = 1.0 - fraction

        return XrVector3d(
            x = previous.bias.x * keep + next.bias.x * fraction,
            y = previous.bias.y * keep + next.bias.y * fraction,
            z = previous.bias.z * keep + next.bias.z * fraction
        )
    }
}

/** One factory gyro-bias sample associated with a calibration temperature. */
data class XrGyroBiasSample(
    val bias: XrVector3d,
    val temperatureCelsius: Double
)

/** IMU intrinsics package for accelerometer and gyroscope. */
data class XrImuIntrinsics(
    val accelerometer: XrSensorIntrinsics,
    val gyroscope: XrSensorIntrinsics,
    val staticDetectionWindowSize: Int,
    val temperatureMeanCelsius: Double
)

/** Generic sensor intrinsics payload including 3x3 calibration matrix. */
data class XrSensorIntrinsics(
    val peakToPeak: XrVector3d,
    val standardDeviation: XrVector3d,
    val bias: XrVector3d,
    val calibrationMatrix3x3: List<Double>
) {
    init {
        require(calibrationMatrix3x3.size == 9) { "calibrationMatrix3x3 must contain 9 values" }
    }
}

/** Rigid transform represented by translation and quaternion rotation. */
data class XrRigidTransform(
    val translation: XrVector3d,
    val rotation: XrQuaternion
)

/** Two-dimensional vector used by config schema fields. */
data class XrVector2d(
    val x: Double,
    val y: Double
)

/** Three-dimensional vector used by config schema fields. */
data class XrVector3d(
    val x: Double,
    val y: Double,
    val z: Double
)

/** Quaternion rotation in `[x, y, z, w]` order. */
data class XrQuaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double
)
