package io.onexr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrControlCommandParityTest {
    @Test
    fun `get property request wire bytes match reference`() {
        assertArrayEquals(
            byteArrayOf(0x18, 0x00),
            XrControlPropertyWire.encodeGetPropertyRequest()
        )
    }

    @Test
    fun `set numeric request wire bytes match reference`() {
        assertArrayEquals(
            byteArrayOf(0x1A, 0x02, 0x08, 0x00),
            XrControlPropertyWire.encodeSetNumericPropertyRequest(0)
        )
        assertArrayEquals(
            byteArrayOf(0x1A, 0x02, 0x08, 0x09),
            XrControlPropertyWire.encodeSetNumericPropertyRequest(9)
        )
        assertArrayEquals(
            byteArrayOf(0x1A, 0x03, 0x08, 0x80.toByte(), 0x01),
            XrControlPropertyWire.encodeSetNumericPropertyRequest(128)
        )
    }

    @Test
    fun `property response parsers decode expected payloads`() {
        XrControlPropertyWire.parseEmptyPropertyResponse(byteArrayOf(0x22, 0x00))
        XrControlPropertyWire.parseEmptyPropertyResponse(byteArrayOf(0x22, 0x02, 0x08, 0x00))

        val numericPayload = byteArrayOf(0x22, 0x02, 0x10, 0x05)
        assertEquals(5, XrControlPropertyWire.parseNumericPropertyResponse(numericPayload))

        val stringPayload = byteArrayOf(
            0x22,
            0x09,
            0x12,
            0x07,
            'o'.code.toByte(),
            'n'.code.toByte(),
            'e'.code.toByte(),
            'p'.code.toByte(),
            'r'.code.toByte(),
            'o'.code.toByte(),
            'x'.code.toByte()
        )
        assertEquals("oneprox", XrControlPropertyWire.parseStringPropertyResponse(stringPayload))
    }

    @Test
    fun `non-empty set response with status becomes command rejected`() {
        val thrown = kotlin.runCatching {
            XrControlPropertyWire.parseEmptyPropertyResponse(
                byteArrayOf(0x22, 0x03, 0x08, 0x91.toByte(), 0x4E)
            )
        }.exceptionOrNull()
        assertTrue(thrown is XrControlProtocolException)
        assertEquals(
            XrControlProtocolErrorCode.COMMAND_REJECTED,
            (thrown as XrControlProtocolException).code
        )
    }

    @Test
    fun `key state payload parser decodes typed event`() {
        val payload = ByteArray(64)
        writeUInt32LittleEndian(payload, 0, 2U)
        writeUInt32LittleEndian(payload, 4, 1U)
        writeUInt32LittleEndian(payload, 8, 1_234_567_890U)

        val event = XrControlInboundParser.parseKeyStateChange(payload)
        assertEquals(XrKeyType.FrontRockerButton, event.keyType)
        assertEquals(XrKeyState.Down, event.keyState)
        assertEquals(1_234_567_890L, event.deviceTimeNs)
    }

    @Test
    fun `key state parser fails on non-64-byte payload`() {
        val thrown = kotlin.runCatching {
            XrControlInboundParser.parseKeyStateChange(ByteArray(63))
        }.exceptionOrNull()

        assertTrue(thrown is XrControlProtocolException)
        assertEquals(
            XrControlProtocolErrorCode.PROTOCOL_ERROR,
            (thrown as XrControlProtocolException).code
        )
    }

    @Test
    fun `command magic constants match xr-tools`() {
        assertEquals(0x2829, XrControlMagic.SET_SCENE_MODE)
        assertEquals(0x2822, XrControlMagic.SET_DISPLAY_INPUT_MODE)
        assertEquals(0x271C, XrControlMagic.SET_BRIGHTNESS)
        assertEquals(0x2727, XrControlMagic.SET_DIMMER)
        assertEquals(0x271F, XrControlMagic.GET_CONFIG)
        assertEquals(0x271D, XrControlMagic.GET_SOFTWARE_VERSION)
        assertEquals(0x272D, XrControlMagic.GET_DSP_VERSION)
        assertEquals(0x2729, XrControlMagic.GET_ID)
        assertEquals(0x272E, XrControlMagic.KEY_STATE_CHANGE)
    }

    private fun writeUInt32LittleEndian(
        target: ByteArray,
        offset: Int,
        value: UInt
    ) {
        target[offset] = (value and 0xFFU).toByte()
        target[offset + 1] = ((value shr 8) and 0xFFU).toByte()
        target[offset + 2] = ((value shr 16) and 0xFFU).toByte()
        target[offset + 3] = ((value shr 24) and 0xFFU).toByte()
    }
}
