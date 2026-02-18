package io.onepro.xr

import org.junit.Assert.assertEquals
import org.junit.Test

class XrDeviceConfigTest {
    @Test
    fun `interpolateGyroBias clamps and interpolates`() {
        val imu = XrImuDevice(
            accelBias = XrVector3d(0.0, 0.0, 0.0),
            biasTemperatureCelsius = 26.0,
            gyroBias = XrVector3d(0.0, 0.0, 0.0),
            gyroBiasTemperatureData = listOf(
                XrGyroBiasSample(XrVector3d(1.0, 2.0, 3.0), 20.0),
                XrGyroBiasSample(XrVector3d(5.0, 6.0, 7.0), 40.0)
            ),
            magnetometerTransform = XrRigidTransform(
                translation = XrVector3d(0.0, 0.0, 0.0),
                rotation = XrQuaternion(0.0, 0.0, 0.0, 1.0)
            ),
            intrinsics = XrImuIntrinsics(
                accelerometer = XrSensorIntrinsics(
                    peakToPeak = XrVector3d(0.0, 0.0, 0.0),
                    standardDeviation = XrVector3d(0.0, 0.0, 0.0),
                    bias = XrVector3d(0.0, 0.0, 0.0),
                    calibrationMatrix3x3 = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
                ),
                gyroscope = XrSensorIntrinsics(
                    peakToPeak = XrVector3d(0.0, 0.0, 0.0),
                    standardDeviation = XrVector3d(0.0, 0.0, 0.0),
                    bias = XrVector3d(0.0, 0.0, 0.0),
                    calibrationMatrix3x3 = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
                ),
                staticDetectionWindowSize = 1,
                temperatureMeanCelsius = 0.0
            ),
            noises = listOf(0.0, 0.0, 0.0, 0.0)
        )

        val low = imu.interpolateGyroBias(10.0)
        assertEquals(1.0, low.x, 1e-6)
        assertEquals(2.0, low.y, 1e-6)
        assertEquals(3.0, low.z, 1e-6)

        val middle = imu.interpolateGyroBias(30.0)
        assertEquals(3.0, middle.x, 1e-6)
        assertEquals(4.0, middle.y, 1e-6)
        assertEquals(5.0, middle.z, 1e-6)

        val high = imu.interpolateGyroBias(50.0)
        assertEquals(5.0, high.x, 1e-6)
        assertEquals(6.0, high.y, 1e-6)
        assertEquals(7.0, high.z, 1e-6)
    }
}
