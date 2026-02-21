package io.onexr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrControlProtocolTest {
    @Test
    fun controlHeaderRoundTripsUsingBigEndianWireFormat() {
        val header = XrControlMessageHeader(
            magic = 0x275E,
            length = 13
        )
        val output = ByteArrayOutputStream()
        header.writeTo(output)

        val bytes = output.toByteArray()
        assertArrayEquals(
            byteArrayOf(
                0x27,
                0x5E,
                0x00,
                0x00,
                0x00,
                0x0D
            ),
            bytes
        )

        val decoded = XrControlMessageHeader.readFrom(ByteArrayInputStream(bytes))
        assertEquals(header.magic, decoded.magic)
        assertEquals(header.length, decoded.length)
    }

    @Test
    fun transactionIdsAreMarkedForOutboundAndNormalizedInbound() {
        val transactionId = 0x123456
        val outbound = XrControlTransactionIds.toOutboundWire(transactionId)

        assertTrue(outbound < 0)
        assertEquals(transactionId, XrControlTransactionIds.normalizeInbound(outbound))
        assertEquals(transactionId, XrControlTransactionIds.normalizeInbound(transactionId))
    }

    @Test
    fun pendingRouterResolvesMatchingTransactionAndMagic() = runBlocking {
        val pending = XrControlPendingRequests()
        val key = XrControlPendingKey(
            transactionId = 17,
            magic = 0x271F
        )
        val response = CompletableDeferred<ByteArray>()
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        assertTrue(pending.register(key, response))
        assertTrue(pending.resolve(key, payload))
        assertArrayEquals(payload, response.await())
    }

    @Test
    fun pendingRouterRejectsUnknownResponseAndCanFailAll() = runBlocking {
        val pending = XrControlPendingRequests()
        val knownKey = XrControlPendingKey(
            transactionId = 3,
            magic = 0x272D
        )
        val unknownKey = XrControlPendingKey(
            transactionId = 4,
            magic = 0x272D
        )
        val response = CompletableDeferred<ByteArray>()

        assertTrue(pending.register(knownKey, response))
        assertFalse(pending.resolve(unknownKey, byteArrayOf(0x00)))

        val failure = IllegalStateException("closed")
        pending.failAll(failure)

        try {
            response.await()
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("closed", expected.message)
        }
    }
}
