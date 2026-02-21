package io.onexr

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

enum class XrControlProtocolErrorCode {
    INVALID_ARGUMENT,
    NETWORK_UNAVAILABLE,
    CONNECTION_FAILED,
    TIMEOUT,
    COMMAND_REJECTED,
    PROTOCOL_ERROR,
    IO_ERROR,
    CONNECTION_CLOSED,
    TRANSACTION_COLLISION
}

class XrControlProtocolException(
    val code: XrControlProtocolErrorCode,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

internal object XrControlMagic {
    const val SET_SCENE_MODE = 0x2829
    const val SET_DISPLAY_INPUT_MODE = 0x2822
    const val SET_BRIGHTNESS = 0x271C
    const val SET_DIMMER = 0x2727
    const val GET_CONFIG = 0x271F
    const val GET_SOFTWARE_VERSION = 0x271D
    const val GET_DSP_VERSION = 0x272D
    const val GET_ID = 0x2729
    const val KEY_STATE_CHANGE = 0x272E
}

internal data class XrControlMessageHeader(
    val magic: Int,
    val length: Int
) {
    init {
        require(magic in 0..0xFFFF) { "magic must be 0..65535" }
        require(length >= 0) { "length must be >= 0" }
    }

    fun writeTo(output: OutputStream) {
        output.write((magic ushr 8) and 0xFF)
        output.write(magic and 0xFF)
        output.write((length ushr 24) and 0xFF)
        output.write((length ushr 16) and 0xFF)
        output.write((length ushr 8) and 0xFF)
        output.write(length and 0xFF)
    }

    companion object {
        const val BYTE_COUNT = 6

        fun readFrom(input: InputStream): XrControlMessageHeader {
            val buffer = input.readExact(BYTE_COUNT)
            val magic = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            val length = readInt32BigEndian(buffer, 2)
            if (length < 0) {
                throw XrControlProtocolException(
                    code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                    message = "Control header has negative length: $length"
                )
            }
            return XrControlMessageHeader(magic = magic, length = length)
        }
    }
}

internal data class XrControlPendingKey(
    val transactionId: Int,
    val magic: Int
)

internal object XrControlTransactionIds {
    fun toOutboundWire(transactionId: Int): Int {
        require(transactionId > 0) { "transactionId must be > 0" }
        return transactionId or Int.MIN_VALUE
    }

    fun normalizeInbound(wireTransactionId: Int): Int {
        return wireTransactionId and Int.MAX_VALUE
    }
}

internal class XrControlPendingRequests {
    private val pending = ConcurrentHashMap<XrControlPendingKey, CompletableDeferred<ByteArray>>()

    fun register(
        key: XrControlPendingKey,
        response: CompletableDeferred<ByteArray>
    ): Boolean {
        return pending.putIfAbsent(key, response) == null
    }

    fun resolve(
        key: XrControlPendingKey,
        payload: ByteArray
    ): Boolean {
        val response = pending.remove(key) ?: return false
        response.complete(payload)
        return true
    }

    fun unregister(
        key: XrControlPendingKey,
        response: CompletableDeferred<ByteArray>
    ) {
        pending.remove(key, response)
    }

    fun failAll(cause: Throwable) {
        pending.entries.forEach { entry ->
            if (pending.remove(entry.key, entry.value)) {
                entry.value.completeExceptionally(cause)
            }
        }
    }
}

internal sealed interface XrControlInboundMessage {
    data class KeyStateChangeRaw(
        val payload: ByteArray
    ) : XrControlInboundMessage

    data class Unknown(
        val magic: Int,
        val transactionId: Int?,
        val payload: ByteArray
    ) : XrControlInboundMessage
}

internal object XrControlPropertyWire {
    private const val ROOT_FIELD_TAG = 0x22
    private const val GET_PROPERTY_TAG = 0x18
    private const val SET_PROPERTY_TAG = 0x1A
    private const val NUMERIC_VALUE_TAG = 0x08
    private const val RESPONSE_NUMERIC_VALUE_TAG = 0x10
    private const val RESPONSE_STRING_VALUE_TAG = 0x12

    fun encodeGetPropertyRequest(): ByteArray {
        return byteArrayOf(GET_PROPERTY_TAG.toByte(), 0x00)
    }

    fun encodeSetNumericPropertyRequest(value: Int): ByteArray {
        if (value < 0) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.INVALID_ARGUMENT,
                message = "Numeric property value must be >= 0"
            )
        }
        val valueBytes = encodeVarint(value.toLong())
        val nestedLength = 1 + valueBytes.size
        val nestedLengthBytes = encodeVarint(nestedLength.toLong())
        val output = ByteArray(1 + nestedLengthBytes.size + nestedLength)
        var offset = 0
        output[offset++] = SET_PROPERTY_TAG.toByte()
        nestedLengthBytes.forEach { output[offset++] = it }
        output[offset++] = NUMERIC_VALUE_TAG.toByte()
        valueBytes.forEach { output[offset++] = it }
        return output
    }

    fun parseEmptyPropertyResponse(payload: ByteArray) {
        val reader = XrControlVarintReader(payload)
        val rootTag = reader.readVarint32()
        if (rootTag != ROOT_FIELD_TAG) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Unexpected property response root tag: 0x${rootTag.toString(16)}"
            )
        }
        val rootLength = reader.readVarint32()
        if (rootLength != 0) {
            val nested = reader.readBytes(rootLength)
            if (!reader.isAtEnd()) {
                throw XrControlProtocolException(
                    code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                    message = "Trailing bytes after non-empty property response body"
                )
            }
            val nestedReader = XrControlVarintReader(nested)
            if (!nestedReader.isAtEnd()) {
                val nestedTag = nestedReader.readVarint32()
                if (nestedTag == 0x08 && !nestedReader.isAtEnd()) {
                    val status = nestedReader.readVarint32()
                    if (nestedReader.isAtEnd()) {
                        if (status == 0) {
                            return
                        }
                        throw XrControlProtocolException(
                            code = XrControlProtocolErrorCode.COMMAND_REJECTED,
                            message = "Property command rejected status=$status (0x${status.toString(16)}) bodyHex=${nested.toHexString()}"
                        )
                    }
                }
            }
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Expected empty property response body length=0 but got $rootLength bodyHex=${nested.toHexString()}"
            )
        }
        if (!reader.isAtEnd()) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Trailing bytes in empty property response"
            )
        }
    }

    fun parseStringPropertyResponse(payload: ByteArray): String {
        val nested = readNestedResponse(payload)
        val tag = nested.readVarint32()
        if (tag != RESPONSE_STRING_VALUE_TAG) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Unexpected string response tag: 0x${tag.toString(16)}"
            )
        }
        val length = nested.readVarint32()
        val bytes = nested.readBytes(length)
        if (!nested.isAtEnd()) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Trailing bytes in string property response body"
            )
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    fun parseNumericPropertyResponse(payload: ByteArray): Int {
        val nested = readNestedResponse(payload)
        val tag = nested.readVarint32()
        if (tag != RESPONSE_NUMERIC_VALUE_TAG) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Unexpected numeric response tag: 0x${tag.toString(16)}"
            )
        }
        val value = nested.readVarint32()
        if (!nested.isAtEnd()) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Trailing bytes in numeric property response body"
            )
        }
        return value
    }

    private fun readNestedResponse(payload: ByteArray): XrControlVarintReader {
        val reader = XrControlVarintReader(payload)
        val rootTag = reader.readVarint32()
        if (rootTag != ROOT_FIELD_TAG) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Unexpected property response root tag: 0x${rootTag.toString(16)}"
            )
        }
        val rootLength = reader.readVarint32()
        val nestedBytes = reader.readBytes(rootLength)
        if (!reader.isAtEnd()) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Trailing bytes after property response payload"
            )
        }
        return XrControlVarintReader(nestedBytes)
    }
}

internal object XrControlInboundParser {
    fun parseKeyStateChange(payload: ByteArray): XrControlEvent.KeyStateChange {
        if (payload.size != 64) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Key state payload must be 64 bytes but got ${payload.size}"
            )
        }
        val keyTypeWire = readUInt32LittleEndian(payload, 0)
        val keyStateWire = readUInt32LittleEndian(payload, 4)
        val deviceTimeWire = readUInt32LittleEndian(payload, 8)
        val keyType = XrKeyType.fromWireValue(keyTypeWire)
            ?: throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Unknown key type value: $keyTypeWire"
            )
        val keyState = XrKeyState.fromWireValue(keyStateWire)
            ?: throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Unknown key state value: $keyStateWire"
            )
        return XrControlEvent.KeyStateChange(
            keyType = keyType,
            keyState = keyState,
            deviceTimeNs = deviceTimeWire.toLong()
        )
    }
}

internal class XrControlVarintReader(
    private val source: ByteArray
) {
    private var offset = 0

    fun isAtEnd(): Boolean {
        return offset == source.size
    }

    fun readBytes(length: Int): ByteArray {
        if (length < 0 || offset + length > source.size) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                message = "Invalid read length=$length at offset=$offset (size=${source.size})"
            )
        }
        val result = source.copyOfRange(offset, offset + length)
        offset += length
        return result
    }

    fun readVarint32(): Int {
        var shift = 0
        var result = 0L
        while (shift < 35) {
            if (offset >= source.size) {
                throw XrControlProtocolException(
                    code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                    message = "Unexpected end while decoding varint"
                )
            }
            val byte = source[offset++].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if ((byte and 0x80) == 0) {
                if (result > Int.MAX_VALUE.toLong()) {
                    throw XrControlProtocolException(
                        code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
                        message = "Varint value exceeds Int range: $result"
                    )
                }
                return result.toInt()
            }
            shift += 7
        }
        throw XrControlProtocolException(
            code = XrControlProtocolErrorCode.PROTOCOL_ERROR,
            message = "Varint exceeds 32-bit length"
        )
    }
}

internal fun encodeVarint(value: Long): ByteArray {
    if (value < 0L) {
        throw IllegalArgumentException("value must be >= 0")
    }
    var remaining = value
    val output = ArrayList<Byte>(10)
    while (true) {
        if ((remaining and 0x7FL.inv()) == 0L) {
            output += remaining.toByte()
            return output.toByteArray()
        }
        output += ((remaining and 0x7F) or 0x80).toByte()
        remaining = remaining ushr 7
    }
}

internal fun InputStream.readExact(length: Int): ByteArray {
    if (length < 0) {
        throw IllegalArgumentException("length must be >= 0")
    }
    val output = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val readCount = read(output, offset, length - offset)
        if (readCount <= 0) {
            throw EOFException("expected $length bytes, got $offset")
        }
        offset += readCount
    }
    return output
}

internal fun OutputStream.writeInt32BigEndian(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

internal fun readInt32BigEndian(
    source: ByteArray,
    offset: Int = 0
): Int {
    require(source.size >= offset + 4) { "source too short for 32-bit read" }
    return ((source[offset].toInt() and 0xFF) shl 24) or
        ((source[offset + 1].toInt() and 0xFF) shl 16) or
        ((source[offset + 2].toInt() and 0xFF) shl 8) or
        (source[offset + 3].toInt() and 0xFF)
}

internal fun readUInt32LittleEndian(
    source: ByteArray,
    offset: Int = 0
): UInt {
    require(source.size >= offset + 4) { "source too short for 32-bit read" }
    return ((source[offset].toUInt() and 0xFFU) shl 0) or
        ((source[offset + 1].toUInt() and 0xFFU) shl 8) or
        ((source[offset + 2].toUInt() and 0xFFU) shl 16) or
        ((source[offset + 3].toUInt() and 0xFFU) shl 24)
}

internal fun ByteArray.toHexString(): String {
    val builder = StringBuilder(size * 2)
    for (value in this) {
        val b = value.toInt() and 0xFF
        builder.append(((b ushr 4) and 0xF).toString(16))
        builder.append((b and 0xF).toString(16))
    }
    return builder.toString()
}
