package io.onepro.xr

import org.junit.Assert.assertEquals
import org.junit.Test

class OneProTrackerSampleMapperTest {
    @Test
    fun fromReportPreservesGyroAndUsesLegacyAccelAxisOrderForTracker() {
        val report = OneProReportMessage(
            deviceId = 1UL,
            hmdTimeNanosDevice = 2UL,
            reportType = OneProReportType.IMU,
            gx = 11.0f,
            gy = 12.0f,
            gz = 13.0f,
            ax = 21.0f,
            ay = 22.0f,
            az = 23.0f,
            mx = 31.0f,
            my = 32.0f,
            mz = 33.0f,
            temperatureCelsius = 30.0f,
            imuId = 7,
            frameId = OneProFrameId(byte0 = 1, byte1 = 2, byte2 = 3)
        )

        val sample = OneProTrackerSampleMapper.fromReport(report)

        assertEquals(11.0f, sample.gx, 0.0001f)
        assertEquals(12.0f, sample.gy, 0.0001f)
        assertEquals(13.0f, sample.gz, 0.0001f)
        assertEquals(23.0f, sample.ax, 0.0001f)
        assertEquals(22.0f, sample.ay, 0.0001f)
        assertEquals(21.0f, sample.az, 0.0001f)
        assertEquals(30.0f, sample.temperatureCelsius, 0.0001f)
    }

    @Test
    fun remapAccelBiasToTrackerFrameUsesLegacyAxisOrder() {
        val remapped = OneProTrackerSampleMapper.remapAccelBiasToTrackerFrame(
            Vector3f(
                x = 1.0f,
                y = 2.0f,
                z = 3.0f
            )
        )

        assertEquals(3.0f, remapped.x, 0.0001f)
        assertEquals(2.0f, remapped.y, 0.0001f)
        assertEquals(1.0f, remapped.z, 0.0001f)
    }

    @Test
    fun remappedBiasSubtractionMatchesRawFrameThenRemapSemantics() {
        val rawAccel = Vector3f(x = 5.0f, y = 3.0f, z = 1.0f)
        val rawBias = Vector3f(x = 0.5f, y = 0.25f, z = -0.75f)

        val mappedRaw = OneProTrackerSampleMapper.remapAccelBiasToTrackerFrame(rawAccel)
        val mappedBias = OneProTrackerSampleMapper.remapAccelBiasToTrackerFrame(rawBias)

        val correctedViaMappedSubtraction = Vector3f(
            x = mappedRaw.x - mappedBias.x,
            y = mappedRaw.y - mappedBias.y,
            z = mappedRaw.z - mappedBias.z
        )
        val correctedRawThenMapped = OneProTrackerSampleMapper.remapAccelBiasToTrackerFrame(
            Vector3f(
                x = rawAccel.x - rawBias.x,
                y = rawAccel.y - rawBias.y,
                z = rawAccel.z - rawBias.z
            )
        )

        assertEquals(correctedRawThenMapped.x, correctedViaMappedSubtraction.x, 0.0001f)
        assertEquals(correctedRawThenMapped.y, correctedViaMappedSubtraction.y, 0.0001f)
        assertEquals(correctedRawThenMapped.z, correctedViaMappedSubtraction.z, 0.0001f)
    }
}
