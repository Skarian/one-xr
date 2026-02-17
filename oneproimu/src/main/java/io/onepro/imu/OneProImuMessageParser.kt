package io.onepro.imu

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object OneProImuMessageParser {
    private val primaryHeader = byteArrayOf(0x28, 0x36, 0x00, 0x00, 0x00, 0x80.toByte())
    private val alternateHeader = byteArrayOf(0x27, 0x36, 0x00, 0x00, 0x00, 0x80.toByte())
    private val headers = arrayOf(primaryHeader, alternateHeader)
    private val sensorMarker = byteArrayOf(0x00, 0x40, 0x1F, 0x00, 0x00, 0x40)

    private const val imuOffsetFromHeader = 28
    private const val nominalFrameBytes = 134
    private const val minImuBytes = 24
    private const val maxPendingBytes = 131_072
    private val minHeaderBytes = headers.minOf { it.size }
    private val maxHeaderBytes = headers.maxOf { it.size }
    private val minimumFrameBytes = minHeaderBytes + imuOffsetFromHeader + minImuBytes + sensorMarker.size

    data class ParseDiagnosticsDelta(
        val droppedBytes: Long = 0,
        val parsedMessageCount: Long = 0,
        val tooShortMessageCount: Long = 0,
        val missingSensorMarkerCount: Long = 0,
        val invalidImuSliceCount: Long = 0,
        val floatDecodeFailureCount: Long = 0
    ) {
        val rejectedMessageCount: Long
            get() {
                return tooShortMessageCount +
                    missingSensorMarkerCount +
                    invalidImuSliceCount +
                    floatDecodeFailureCount
            }
    }

    data class AppendResult(
        val imuSamples: List<OneProImuSample>,
        val diagnosticsDelta: ParseDiagnosticsDelta
    )

    class StreamFramer {
        private var pending = ByteArray(0)

        fun append(chunk: ByteArray): AppendResult {
            if (chunk.isEmpty()) {
                return AppendResult(emptyList(), ParseDiagnosticsDelta())
            }

            pending += chunk
            val imuSamples = mutableListOf<OneProImuSample>()
            var droppedBytes = 0L
            var parsedMessageCount = 0L
            var tooShortMessageCount = 0L
            var missingSensorMarkerCount = 0L
            var invalidImuSliceCount = 0L
            var floatDecodeFailureCount = 0L

            while (pending.size >= minHeaderBytes) {
                val firstHeaderMatch = pending.findHeaderMatch()
                if (firstHeaderMatch == null) {
                    val keep = (maxHeaderBytes - 1).coerceAtMost(pending.size)
                    droppedBytes += (pending.size - keep).toLong()
                    pending = if (keep == 0) {
                        ByteArray(0)
                    } else {
                        pending.copyOfRange(pending.size - keep, pending.size)
                    }
                    break
                }

                if (firstHeaderMatch.index > 0) {
                    droppedBytes += firstHeaderMatch.index.toLong()
                    pending = pending.copyOfRange(firstHeaderMatch.index, pending.size)
                }

                val activeHeaderMatch = pending.findHeaderMatch()
                if (activeHeaderMatch == null || activeHeaderMatch.index != 0) {
                    droppedBytes += 1
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }

                val nextHeaderIndex = pending.findHeaderMatch(startIndex = activeHeaderMatch.header.size)?.index ?: -1
                val message = if (nextHeaderIndex > 0) {
                    pending.copyOfRange(0, nextHeaderIndex)
                } else {
                    pending
                }

                when (val outcome = decodeMessage(message, activeHeaderMatch.header)) {
                    is DecodeOutcome.Success -> {
                        parsedMessageCount += 1
                        imuSamples += outcome.sample
                        val consumeCount = when {
                            nextHeaderIndex > 0 -> nextHeaderIndex
                            pending.size >= nominalFrameBytes -> nominalFrameBytes
                            else -> outcome.consumedByteCount.coerceIn(1, pending.size)
                        }
                        pending = pending.copyOfRange(consumeCount, pending.size)
                    }

                    DecodeOutcome.TooShort -> {
                        tooShortMessageCount += 1
                        if (nextHeaderIndex > 0) {
                            droppedBytes += nextHeaderIndex.toLong()
                            pending = pending.copyOfRange(nextHeaderIndex, pending.size)
                            continue
                        }
                        break
                    }

                    DecodeOutcome.MissingSensorMarker -> {
                        missingSensorMarkerCount += 1
                        if (nextHeaderIndex > 0) {
                            droppedBytes += nextHeaderIndex.toLong()
                            pending = pending.copyOfRange(nextHeaderIndex, pending.size)
                        } else {
                            break
                        }
                    }

                    DecodeOutcome.InvalidImuSlice -> {
                        invalidImuSliceCount += 1
                        if (nextHeaderIndex > 0) {
                            droppedBytes += nextHeaderIndex.toLong()
                            pending = pending.copyOfRange(nextHeaderIndex, pending.size)
                        } else {
                            droppedBytes += 1
                            pending = pending.copyOfRange(1, pending.size)
                        }
                    }

                    DecodeOutcome.FloatDecodeFailure -> {
                        floatDecodeFailureCount += 1
                        if (nextHeaderIndex > 0) {
                            droppedBytes += nextHeaderIndex.toLong()
                            pending = pending.copyOfRange(nextHeaderIndex, pending.size)
                        } else {
                            droppedBytes += 1
                            pending = pending.copyOfRange(1, pending.size)
                        }
                    }
                }

                if (pending.size > maxPendingBytes) {
                    val keep = maxPendingBytes.coerceAtLeast(maxHeaderBytes - 1)
                    val drop = pending.size - keep
                    droppedBytes += drop.toLong()
                    pending = pending.copyOfRange(drop, pending.size)
                }
            }

            if (pending.size > maxPendingBytes) {
                val keep = maxPendingBytes.coerceAtLeast(maxHeaderBytes - 1)
                val drop = pending.size - keep
                droppedBytes += drop.toLong()
                pending = pending.copyOfRange(drop, pending.size)
            }

            return AppendResult(
                imuSamples = imuSamples,
                diagnosticsDelta = ParseDiagnosticsDelta(
                    droppedBytes = droppedBytes,
                    parsedMessageCount = parsedMessageCount,
                    tooShortMessageCount = tooShortMessageCount,
                    missingSensorMarkerCount = missingSensorMarkerCount,
                    invalidImuSliceCount = invalidImuSliceCount,
                    floatDecodeFailureCount = floatDecodeFailureCount
                )
            )
        }
    }

    private sealed interface DecodeOutcome {
        data class Success(
            val sample: OneProImuSample,
            val consumedByteCount: Int
        ) : DecodeOutcome
        data object TooShort : DecodeOutcome
        data object MissingSensorMarker : DecodeOutcome
        data object InvalidImuSlice : DecodeOutcome
        data object FloatDecodeFailure : DecodeOutcome
    }

    private data class HeaderMatch(
        val index: Int,
        val header: ByteArray
    )

    private fun decodeMessage(message: ByteArray, header: ByteArray): DecodeOutcome {
        val imuStartOffset = header.size + imuOffsetFromHeader
        val markerSearchStart = imuStartOffset + minImuBytes
        val minimumBytesForHeader = header.size + imuOffsetFromHeader + minImuBytes + sensorMarker.size
        if (message.size < minimumBytesForHeader || message.size < minimumFrameBytes) {
            return DecodeOutcome.TooShort
        }

        val sensorMarkerIndex = message.indexOf(sensorMarker, startIndex = markerSearchStart)
        if (sensorMarkerIndex < 0) {
            return DecodeOutcome.MissingSensorMarker
        }
        val imuSliceLength = sensorMarkerIndex - imuStartOffset
        if (imuSliceLength < minImuBytes) {
            return DecodeOutcome.InvalidImuSlice
        }

        val values = try {
            ByteBuffer.wrap(message, imuStartOffset, minImuBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .let { buffer ->
                    FloatArray(6) { buffer.float }
                }
        } catch (_: Throwable) {
            return DecodeOutcome.FloatDecodeFailure
        }

        return DecodeOutcome.Success(
            OneProImuSample(
                gx = values[0],
                gy = values[1],
                gz = values[2],
                ax = values[5],
                ay = values[4],
                az = values[3]
            ),
            consumedByteCount = sensorMarkerIndex + sensorMarker.size
        )
    }

    private fun ByteArray.findHeaderMatch(startIndex: Int = 0): HeaderMatch? {
        var selected: HeaderMatch? = null
        headers.forEach { header ->
            val index = indexOf(header, startIndex = startIndex)
            if (index >= 0) {
                val current = selected
                if (current == null || index < current.index) {
                    selected = HeaderMatch(index, header)
                }
            }
        }
        return selected
    }

    private fun ByteArray.indexOf(pattern: ByteArray, startIndex: Int = 0): Int {
        if (size < pattern.size) {
            return -1
        }
        val boundedStart = startIndex.coerceAtLeast(0)
        val lastStart = size - pattern.size
        if (boundedStart > lastStart) {
            return -1
        }
        for (start in boundedStart..lastStart) {
            var matches = true
            for (offset in pattern.indices) {
                if (this[start + offset] != pattern[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return start
            }
        }
        return -1
    }
}
