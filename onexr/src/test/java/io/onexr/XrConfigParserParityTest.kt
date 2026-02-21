package io.onexr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrConfigParserParityTest {
    @Test
    fun `parser decodes full config with rgb and slam cameras`() {
        val config = XrDeviceConfigParser.parse(validConfigWithCameras())

        assertEquals(8, config.glassesVersion)
        assertEquals("KTEST12345", config.fsn)
        assertNotNull(config.rgbCamera)
        assertNotNull(config.slamCamera)
        assertEquals(2, config.displayDistortion.leftDisplay.points.size)
        assertEquals(2, config.displayDistortion.rightDisplay.points.size)
        assertEquals(2, config.imu.gyroBiasTemperatureData.size)
        assertEquals(0.1, config.imu.interpolateGyroBias(25.0).x, 1e-6)
        assertEquals(0.2, config.imu.interpolateGyroBias(25.0).y, 1e-6)
        assertEquals(0.3, config.imu.interpolateGyroBias(25.0).z, 1e-6)
    }

    @Test
    fun `parser decodes config without optional cameras`() {
        val config = XrDeviceConfigParser.parse(validConfigWithoutCameras())

        assertNull(config.rgbCamera)
        assertNull(config.slamCamera)
        assertEquals(2, config.displayDistortion.leftDisplay.points.size)
    }

    @Test
    fun `parser rejects malformed json`() {
        try {
            XrDeviceConfigParser.parse("{")
            throw AssertionError("expected parse error")
        } catch (expected: XrDeviceConfigException) {
            assertEquals(XrDeviceConfigErrorCode.PARSE_ERROR, expected.code)
        }
    }

    @Test
    fun `parser rejects unsupported glasses version`() {
        val raw = validConfigWithCameras().replace("\"glasses_version\": 8", "\"glasses_version\": 7")

        try {
            XrDeviceConfigParser.parse(raw)
            throw AssertionError("expected schema validation error")
        } catch (expected: XrDeviceConfigException) {
            assertEquals(XrDeviceConfigErrorCode.SCHEMA_VALIDATION_ERROR, expected.code)
            assertTrue(expected.message?.contains("glasses_version") == true)
        }
    }

    @Test
    fun `parser rejects invalid distortion grid shape`() {
        val raw = validConfigWithCameras().replace(
            "\"data\": [0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 2.0, 2.0]",
            "\"data\": [0.0, 0.0, 1.0, 1.0]"
        )

        try {
            XrDeviceConfigParser.parse(raw)
            throw AssertionError("expected schema validation error")
        } catch (expected: XrDeviceConfigException) {
            assertEquals(XrDeviceConfigErrorCode.SCHEMA_VALIDATION_ERROR, expected.code)
            assertTrue(expected.message?.contains("num_row*num_col") == true)
        }
    }

    private fun validConfigWithCameras(): String {
        return """
            {
              "glasses_version": 8,
              "FSN": "KTEST12345",
              "last_modified_time": "2026-02-18 11:12:13",
              "IMU": {
                "num_of_imus": 1,
                "device_1": {
                  "accel_bias": [0.01, 0.02, 0.03],
                  "bias_temperature": 26.0,
                  "gyro_bias": [0.001, 0.002, 0.003],
                  "gyro_bias_temp_data": [
                    { "bias": [0.0, 0.0, 0.0], "temp": 20.0 },
                    { "bias": [0.2, 0.4, 0.6], "temp": 30.0 }
                  ],
                  "gyro_p_mag": [0.1, 0.2, 0.3],
                  "gyro_q_mag": [0.0, 0.0, 0.0, 1.0],
                  "imu_intrinsics": {
                    "accel_pkpk": [1.0, 1.1, 1.2],
                    "accel_std": [0.1, 0.2, 0.3],
                    "accl_bias": [0.01, 0.02, 0.03],
                    "accl_calib_mat": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
                    "gyro_pkpk": [2.0, 2.1, 2.2],
                    "gyro_std": [0.4, 0.5, 0.6],
                    "gyro_bias": [0.04, 0.05, 0.06],
                    "gyro_calib_mat": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
                    "static_detection_window_size": 32,
                    "temperature_mean": 25.5
                  },
                  "imu_noises": [0.0001, 0.0002, 0.0003, 0.0004],
                  "accel_q_gyro": [0.0, 0.0, 0.0, 1.0],
                  "gyro_g_sensitivity": [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
                  "mag_bias": [0.0, 0.0, 0.0],
                  "scale_accel": [1.0, 1.0, 1.0],
                  "scale_gyro": [1.0, 1.0, 1.0],
                  "scale_mag": [1.0, 1.0, 1.0],
                  "skew_accel": [0.0, 0.0, 0.0],
                  "skew_gyro": [0.0, 0.0, 0.0],
                  "skew_mag": [0.0, 0.0, 0.0]
                }
              },
              "RGB_camera": {
                "num_of_cameras": 1,
                "device_1": {
                  "camera_model": "radial",
                  "cc": [100.0, 200.0],
                  "fc": [300.0, 400.0],
                  "kc": [0.1, 0.2, 0.3, 0.4, 0.5],
                  "resolution": [1920.0, 1080.0],
                  "rolling_shutter_time": 0.002
                }
              },
              "SLAM_camera": {
                "num_of_cameras": 1,
                "device_1": {
                  "camera_model": "radial",
                  "cc": [10.0, 20.0],
                  "fc": [30.0, 40.0],
                  "kc": [0.01, 0.02, 0.03, 0.04, 0.05],
                  "resolution": [640.0, 480.0],
                  "rolling_shutter_time": 0.003,
                  "imu_p_cam": [0.5, 0.6, 0.7],
                  "imu_q_cam": [0.0, 0.0, 0.0, 1.0]
                }
              },
              "display": {
                "target_type": "IMU",
                "num_of_displays": 2,
                "resolution": [1920.0, 1080.0],
                "k_left_display": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
                "k_right_display": [2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0],
                "target_p_left_display": [-0.1, 0.0, -0.3],
                "target_q_left_display": [0.0, 0.0, 0.0, 1.0],
                "target_p_right_display": [0.1, 0.0, -0.3],
                "target_q_right_display": [0.0, 0.0, 0.0, 1.0]
              },
              "display_distortion": {
                "left_display": {
                  "type": 1,
                  "num_row": 1,
                  "num_col": 2,
                  "data": [0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 2.0, 2.0]
                },
                "right_display": {
                  "type": 1,
                  "num_row": 1,
                  "num_col": 2,
                  "data": [0.0, 0.0, 3.0, 3.0, 1.0, 0.0, 4.0, 4.0]
                }
              }
            }
        """.trimIndent()
    }

    private fun validConfigWithoutCameras(): String {
        return """
            {
              "glasses_version": 8,
              "FSN": "KTEST12345",
              "last_modified_time": "2026-02-18 11:12:13",
              "IMU": {
                "num_of_imus": 1,
                "device_1": {
                  "accel_bias": [0.01, 0.02, 0.03],
                  "bias_temperature": 26.0,
                  "gyro_bias": [0.001, 0.002, 0.003],
                  "gyro_bias_temp_data": [
                    { "bias": [0.0, 0.0, 0.0], "temp": 20.0 },
                    { "bias": [0.2, 0.4, 0.6], "temp": 30.0 }
                  ],
                  "gyro_p_mag": [0.1, 0.2, 0.3],
                  "gyro_q_mag": [0.0, 0.0, 0.0, 1.0],
                  "imu_intrinsics": {
                    "accel_pkpk": [1.0, 1.1, 1.2],
                    "accel_std": [0.1, 0.2, 0.3],
                    "accl_bias": [0.01, 0.02, 0.03],
                    "accl_calib_mat": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
                    "gyro_pkpk": [2.0, 2.1, 2.2],
                    "gyro_std": [0.4, 0.5, 0.6],
                    "gyro_bias": [0.04, 0.05, 0.06],
                    "gyro_calib_mat": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
                    "static_detection_window_size": 32,
                    "temperature_mean": 25.5
                  },
                  "imu_noises": [0.0001, 0.0002, 0.0003, 0.0004],
                  "accel_q_gyro": [0.0, 0.0, 0.0, 1.0],
                  "gyro_g_sensitivity": [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
                  "mag_bias": [0.0, 0.0, 0.0],
                  "scale_accel": [1.0, 1.0, 1.0],
                  "scale_gyro": [1.0, 1.0, 1.0],
                  "scale_mag": [1.0, 1.0, 1.0],
                  "skew_accel": [0.0, 0.0, 0.0],
                  "skew_gyro": [0.0, 0.0, 0.0],
                  "skew_mag": [0.0, 0.0, 0.0]
                }
              },
              "RGB_camera": { "num_of_cameras": 0 },
              "SLAM_camera": { "num_of_cameras": 0 },
              "display": {
                "target_type": "IMU",
                "num_of_displays": 2,
                "resolution": [1920.0, 1080.0],
                "k_left_display": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
                "k_right_display": [2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0],
                "target_p_left_display": [-0.1, 0.0, -0.3],
                "target_q_left_display": [0.0, 0.0, 0.0, 1.0],
                "target_p_right_display": [0.1, 0.0, -0.3],
                "target_q_right_display": [0.0, 0.0, 0.0, 1.0]
              },
              "display_distortion": {
                "left_display": {
                  "type": 1,
                  "num_row": 1,
                  "num_col": 2,
                  "data": [0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 2.0, 2.0]
                },
                "right_display": {
                  "type": 1,
                  "num_row": 1,
                  "num_col": 2,
                  "data": [0.0, 0.0, 3.0, 3.0, 1.0, 0.0, 4.0, 4.0]
                }
              }
            }
        """.trimIndent()
    }
}
