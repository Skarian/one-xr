package io.onexr

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object XrDeviceConfigParser {
    private val SUPPORTED_GLASSES_VERSIONS = setOf(7, 8)
    private val lastModifiedTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parse(rawJson: String): XrDeviceConfig {
        val root = try {
            JSONObject(rawJson)
        } catch (error: JSONException) {
            throw XrDeviceConfigException(
                code = XrDeviceConfigErrorCode.PARSE_ERROR,
                message = "Invalid config JSON: ${error.message ?: "no-message"}",
                cause = error
            )
        }

        val glassesVersion = root.requireInt("glasses_version", "$")
        if (glassesVersion !in SUPPORTED_GLASSES_VERSIONS) {
            throw schemaError(
                path = "$.glasses_version",
                detail = "must be one of ${SUPPORTED_GLASSES_VERSIONS.sorted()} but was $glassesVersion"
            )
        }

        val fsn = root.requireString("FSN", "$")
        val lastModifiedTime = parseLastModifiedTime(root.requireString("last_modified_time", "$"))
        val display = parseDisplaysConfig(root.requireObject("display", "$"), "$.display")
        val displayDistortion = parseDisplaysDistortion(
            root.requireObject("display_distortion", "$"),
            "$.display_distortion"
        )
        val rgbCamera = parseOptionalRgbCamera(root.requireObject("RGB_camera", "$"), "$.RGB_camera")
        val slamCamera = parseOptionalSlamCamera(root.requireObject("SLAM_camera", "$"), "$.SLAM_camera")
        val imu = parseImuDevice(root.requireObject("IMU", "$"), "$.IMU")

        return XrDeviceConfig(
            glassesVersion = glassesVersion,
            fsn = fsn,
            lastModifiedTime = lastModifiedTime,
            display = display,
            displayDistortion = displayDistortion,
            rgbCamera = rgbCamera,
            slamCamera = slamCamera,
            imu = imu,
            rawJson = rawJson
        )
    }

    private fun parseLastModifiedTime(raw: String): LocalDateTime {
        return try {
            LocalDateTime.parse(raw, lastModifiedTimeFormatter)
        } catch (error: DateTimeParseException) {
            throw schemaError(
                path = "$.last_modified_time",
                detail = "must match format yyyy-MM-dd HH:mm:ss"
            )
        }
    }

    private fun parseDisplaysConfig(node: JSONObject, path: String): XrDisplaysConfig {
        val targetType = node.requireString("target_type", path)
        if (targetType != "IMU") {
            throw schemaError("$path.target_type", "must be IMU but was $targetType")
        }

        val displayCount = node.requireInt("num_of_displays", path)
        if (displayCount != 2) {
            throw schemaError("$path.num_of_displays", "must be 2 but was $displayCount")
        }

        return XrDisplaysConfig(
            resolution = node.requireVector2("resolution", path),
            left = XrDisplayConfig(
                intrinsicsMatrix3x3 = node.requireDoubleList("k_left_display", 9, path),
                transform = XrRigidTransform(
                    translation = node.requireVector3("target_p_left_display", path),
                    rotation = node.requireQuaternion("target_q_left_display", path)
                )
            ),
            right = XrDisplayConfig(
                intrinsicsMatrix3x3 = node.requireDoubleList("k_right_display", 9, path),
                transform = XrRigidTransform(
                    translation = node.requireVector3("target_p_right_display", path),
                    rotation = node.requireQuaternion("target_q_right_display", path)
                )
            )
        )
    }

    private fun parseDisplaysDistortion(node: JSONObject, path: String): XrDisplaysDistortion {
        return XrDisplaysDistortion(
            leftDisplay = parseDisplayDistortion(
                node.requireObject("left_display", path),
                "$path.left_display"
            ),
            rightDisplay = parseDisplayDistortion(
                node.requireObject("right_display", path),
                "$path.right_display"
            )
        )
    }

    private fun parseDisplayDistortion(node: JSONObject, path: String): XrDisplayDistortion {
        val typ = node.requireInt("type", path)
        if (typ != 1) {
            throw schemaError("$path.type", "must be 1 but was $typ")
        }

        val numRows = node.requireInt("num_row", path)
        val numCols = node.requireInt("num_col", path)
        if (numRows <= 0) {
            throw schemaError("$path.num_row", "must be > 0")
        }
        if (numCols <= 0) {
            throw schemaError("$path.num_col", "must be > 0")
        }

        val data = node.requireDoubleList("data", expectedSize = null, path = path)
        if (data.size % 4 != 0) {
            throw schemaError("$path.data", "length must be divisible by 4")
        }

        val expectedPointCount = numRows * numCols
        val pointCount = data.size / 4
        if (pointCount != expectedPointCount) {
            throw schemaError(
                path = "$path.data",
                detail = "point count $pointCount does not match num_row*num_col ($expectedPointCount)"
            )
        }

        val points = data.chunked(4).map { chunk ->
            XrDistortionPoint(
                u = chunk[0],
                v = chunk[1],
                x = chunk[2],
                y = chunk[3]
            )
        }

        return XrDisplayDistortion(
            numRows = numRows,
            numCols = numCols,
            points = points
        )
    }

    private fun parseOptionalRgbCamera(node: JSONObject, path: String): XrCameraIntrinsicsRadial? {
        val numOfCameras = node.requireInt("num_of_cameras", path)
        val device = node.optionalObject("device_1", path) ?: return null
        if (numOfCameras != 1) {
            throw schemaError("$path.num_of_cameras", "must be 1 when device_1 is present")
        }
        return parseCameraIntrinsicsRadial(device, "$path.device_1")
    }

    private fun parseOptionalSlamCamera(node: JSONObject, path: String): XrSlamCamera? {
        val numOfCameras = node.requireInt("num_of_cameras", path)
        val device = node.optionalObject("device_1", path) ?: return null
        if (numOfCameras != 1) {
            throw schemaError("$path.num_of_cameras", "must be 1 when device_1 is present")
        }

        return XrSlamCamera(
            cameraTransform = XrRigidTransform(
                translation = device.requireVector3("imu_p_cam", "$path.device_1"),
                rotation = device.requireQuaternion("imu_q_cam", "$path.device_1")
            ),
            intrinsics = parseCameraIntrinsicsRadial(device, "$path.device_1")
        )
    }

    private fun parseCameraIntrinsicsRadial(node: JSONObject, path: String): XrCameraIntrinsicsRadial {
        val cameraModel = node.requireString("camera_model", path)
        if (cameraModel != "radial") {
            throw schemaError("$path.camera_model", "must be radial but was $cameraModel")
        }

        val kcValues = node.requireDoubleList("kc", 5, path)
        return XrCameraIntrinsicsRadial(
            principalPoint = node.requireVector2("cc", path),
            focalLength = node.requireVector2("fc", path),
            distortion = XrDistortionCoefficients(
                k1 = kcValues[0],
                k2 = kcValues[1],
                p1 = kcValues[2],
                p2 = kcValues[3],
                k3 = kcValues[4]
            ),
            resolution = node.requireVector2("resolution", path),
            rollingShutterTimeSeconds = node.requireDouble("rolling_shutter_time", path)
        )
    }

    private fun parseImuDevice(node: JSONObject, path: String): XrImuDevice {
        val imuCount = node.requireInt("num_of_imus", path)
        if (imuCount != 1) {
            throw schemaError("$path.num_of_imus", "must be 1 but was $imuCount")
        }

        val device = node.requireObject("device_1", path)
        requireExactVector(device, "accel_q_gyro", "$path.device_1", listOf(0.0, 0.0, 0.0, 1.0))
        requireExactVector(device, "gyro_g_sensitivity", "$path.device_1", List(9) { 0.0 })
        requireExactVector(device, "mag_bias", "$path.device_1", List(3) { 0.0 })
        requireExactVector(device, "scale_accel", "$path.device_1", List(3) { 1.0 })
        requireExactVector(device, "scale_gyro", "$path.device_1", List(3) { 1.0 })
        requireExactVector(device, "scale_mag", "$path.device_1", List(3) { 1.0 })
        requireExactVector(device, "skew_accel", "$path.device_1", List(3) { 0.0 })
        requireExactVector(device, "skew_gyro", "$path.device_1", List(3) { 0.0 })
        requireExactVector(device, "skew_mag", "$path.device_1", List(3) { 0.0 })

        val gyroBiasTempData = parseGyroBiasTemperatureData(
            device.requireArray("gyro_bias_temp_data", "$path.device_1"),
            "$path.device_1.gyro_bias_temp_data"
        )

        return XrImuDevice(
            accelBias = device.requireVector3("accel_bias", "$path.device_1"),
            biasTemperatureCelsius = device.requireDouble("bias_temperature", "$path.device_1"),
            gyroBias = device.requireVector3("gyro_bias", "$path.device_1"),
            gyroBiasTemperatureData = gyroBiasTempData,
            magnetometerTransform = XrRigidTransform(
                translation = device.requireVector3("gyro_p_mag", "$path.device_1"),
                rotation = device.requireQuaternion("gyro_q_mag", "$path.device_1")
            ),
            intrinsics = parseImuIntrinsics(
                device.requireObject("imu_intrinsics", "$path.device_1"),
                "$path.device_1.imu_intrinsics"
            ),
            noises = device.requireDoubleList("imu_noises", 4, "$path.device_1")
        )
    }

    private fun parseGyroBiasTemperatureData(array: JSONArray, path: String): List<XrGyroBiasSample> {
        if (array.length() == 0) {
            throw schemaError(path, "must contain at least one entry")
        }
        val items = buildList {
            for (index in 0 until array.length()) {
                val node = array.requireObject(index, path)
                add(
                    XrGyroBiasSample(
                        bias = node.requireVector3("bias", "$path[$index]"),
                        temperatureCelsius = node.requireDouble("temp", "$path[$index]")
                    )
                )
            }
        }

        for (index in 1 until items.size) {
            if (items[index - 1].temperatureCelsius > items[index].temperatureCelsius) {
                throw schemaError(path, "temperatures must be sorted in non-decreasing order")
            }
        }
        return items
    }

    private fun parseImuIntrinsics(node: JSONObject, path: String): XrImuIntrinsics {
        return XrImuIntrinsics(
            accelerometer = XrSensorIntrinsics(
                peakToPeak = node.requireVector3("accel_pkpk", path),
                standardDeviation = node.requireVector3("accel_std", path),
                bias = node.requireVector3("accl_bias", path),
                calibrationMatrix3x3 = node.requireDoubleList("accl_calib_mat", 9, path)
            ),
            gyroscope = XrSensorIntrinsics(
                peakToPeak = node.requireVector3("gyro_pkpk", path),
                standardDeviation = node.requireVector3("gyro_std", path),
                bias = node.requireVector3("gyro_bias", path),
                calibrationMatrix3x3 = node.requireDoubleList("gyro_calib_mat", 9, path)
            ),
            staticDetectionWindowSize = node.requireInt("static_detection_window_size", path),
            temperatureMeanCelsius = node.requireDouble("temperature_mean", path)
        )
    }

    private fun requireExactVector(
        node: JSONObject,
        key: String,
        path: String,
        expected: List<Double>
    ) {
        val actual = node.requireDoubleList(key, expected.size, path)
        if (actual != expected) {
            throw schemaError("$path.$key", "must be $expected but was $actual")
        }
    }

    private fun JSONObject.requireObject(key: String, path: String): JSONObject {
        val value = opt(key)
        if (value is JSONObject) {
            return value
        }
        throw schemaError("$path.$key", "must be an object")
    }

    private fun JSONObject.optionalObject(key: String, path: String): JSONObject? {
        if (!has(key) || isNull(key)) {
            return null
        }
        val value = opt(key)
        if (value is JSONObject) {
            return value
        }
        throw schemaError("$path.$key", "must be an object when present")
    }

    private fun JSONObject.requireArray(key: String, path: String): JSONArray {
        val value = opt(key)
        if (value is JSONArray) {
            return value
        }
        throw schemaError("$path.$key", "must be an array")
    }

    private fun JSONObject.requireString(key: String, path: String): String {
        val value = opt(key)
        if (value is String) {
            return value
        }
        throw schemaError("$path.$key", "must be a string")
    }

    private fun JSONObject.requireInt(key: String, path: String): Int {
        val value = opt(key)
        val number = value as? Number ?: throw schemaError("$path.$key", "must be an integer")
        val intValue = number.toInt()
        if (intValue.toDouble() != number.toDouble()) {
            throw schemaError("$path.$key", "must be an integer")
        }
        return intValue
    }

    private fun JSONObject.requireDouble(key: String, path: String): Double {
        val value = opt(key)
        val number = value as? Number ?: throw schemaError("$path.$key", "must be numeric")
        val result = number.toDouble()
        if (!result.isFinite()) {
            throw schemaError("$path.$key", "must be finite")
        }
        return result
    }

    private fun JSONObject.requireDoubleList(
        key: String,
        expectedSize: Int?,
        path: String
    ): List<Double> {
        val array = requireArray(key, path)
        return parseDoubleList(array, "$path.$key", expectedSize)
    }

    private fun JSONObject.requireVector2(key: String, path: String): XrVector2d {
        val values = requireDoubleList(key, 2, path)
        return XrVector2d(values[0], values[1])
    }

    private fun JSONObject.requireVector3(key: String, path: String): XrVector3d {
        val values = requireDoubleList(key, 3, path)
        return XrVector3d(values[0], values[1], values[2])
    }

    private fun JSONObject.requireQuaternion(key: String, path: String): XrQuaternion {
        val values = requireDoubleList(key, 4, path)
        return XrQuaternion(values[0], values[1], values[2], values[3])
    }

    private fun parseDoubleList(array: JSONArray, path: String, expectedSize: Int?): List<Double> {
        if (expectedSize != null && array.length() != expectedSize) {
            throw schemaError(path, "must contain $expectedSize values but has ${array.length()}")
        }
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.opt(index)
                val number = value as? Number ?: throw schemaError("$path[$index]", "must be numeric")
                val numeric = number.toDouble()
                if (!numeric.isFinite()) {
                    throw schemaError("$path[$index]", "must be finite")
                }
                add(numeric)
            }
        }
    }

    private fun JSONArray.requireObject(index: Int, path: String): JSONObject {
        val value = opt(index)
        if (value is JSONObject) {
            return value
        }
        throw schemaError("$path[$index]", "must be an object")
    }

    private fun schemaError(path: String, detail: String): XrDeviceConfigException {
        return XrDeviceConfigException(
            code = XrDeviceConfigErrorCode.SCHEMA_VALIDATION_ERROR,
            message = "Invalid device config at $path: $detail"
        )
    }
}
