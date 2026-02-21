package io.onexr

internal object OneXrTrackerSampleMapper {
    fun fromReport(report: OneXrReportMessage): OneXrImuVectorSample {
        val trackerFrameAccel = remapAccelToTrackerFrame(
            ax = report.ax,
            ay = report.ay,
            az = report.az
        )
        return OneXrImuVectorSample(
            gx = report.gx,
            gy = report.gy,
            gz = report.gz,
            ax = trackerFrameAccel.x,
            ay = trackerFrameAccel.y,
            az = trackerFrameAccel.z,
            temperatureCelsius = report.temperatureCelsius
        )
    }

    fun remapAccelBiasToTrackerFrame(factoryAccelBias: Vector3f): Vector3f {
        return remapAccelToTrackerFrame(
            ax = factoryAccelBias.x,
            ay = factoryAccelBias.y,
            az = factoryAccelBias.z
        )
    }

    private fun remapAccelToTrackerFrame(ax: Float, ay: Float, az: Float): Vector3f {
        return Vector3f(
            x = az,
            y = ay,
            z = ax
        )
    }
}
