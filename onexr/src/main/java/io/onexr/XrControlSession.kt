package io.onexr

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal data class XrControlSessionInfo(
    val networkHandle: Long,
    val interfaceName: String,
    val localSocket: String,
    val remoteSocket: String,
    val connectMs: Long
)

internal class XrControlSession private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val info: XrControlSessionInfo,
    private val defaultRequestTimeoutMs: Long
) {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerMutex = Mutex()
    private val pendingRequests = XrControlPendingRequests()
    private val nextTransactionId = AtomicInteger(1)
    private val closed = AtomicBoolean(false)

    private val _inboundMessages = MutableSharedFlow<XrControlInboundMessage>(extraBufferCapacity = 128)
    val inboundMessages: SharedFlow<XrControlInboundMessage>
        get() = _inboundMessages.asSharedFlow()

    init {
        sessionScope.launch {
            runReaderLoop()
        }
    }

    fun isClosed(): Boolean {
        return closed.get()
    }

    suspend fun sendTransaction(
        magic: Int,
        requestBody: ByteArray,
        timeoutMs: Long = defaultRequestTimeoutMs
    ): ByteArray {
        if (isClosed()) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.CONNECTION_CLOSED,
                message = "Control session is closed"
            )
        }
        if (timeoutMs <= 0L) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.INVALID_ARGUMENT,
                message = "Control timeout must be > 0"
            )
        }

        val transactionId = allocateTransactionId()
        val response = CompletableDeferred<ByteArray>()
        val key = XrControlPendingKey(transactionId = transactionId, magic = magic)

        if (!pendingRequests.register(key, response)) {
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.TRANSACTION_COLLISION,
                message = "Duplicate pending control transaction id=$transactionId magic=${magic.toString(16)}"
            )
        }

        try {
            val header = XrControlMessageHeader(
                magic = magic,
                length = requestBody.size + 4
            )
            writerMutex.withLock {
                if (isClosed()) {
                    throw XrControlProtocolException(
                        code = XrControlProtocolErrorCode.CONNECTION_CLOSED,
                        message = "Control session closed while writing"
                    )
                }
                header.writeTo(output)
                output.writeInt32BigEndian(XrControlTransactionIds.toOutboundWire(transactionId))
                output.write(requestBody)
                output.flush()
            }
            return try {
                withTimeout(timeoutMs) {
                    response.await()
                }
            } catch (t: TimeoutCancellationException) {
                throw XrControlProtocolException(
                    code = XrControlProtocolErrorCode.TIMEOUT,
                    message = "Control response timeout for magic=${magic.toString(16)} tx=$transactionId",
                    cause = t
                )
            }
        } catch (t: Throwable) {
            if (t is XrControlProtocolException) {
                throw t
            }
            throw XrControlProtocolException(
                code = XrControlProtocolErrorCode.IO_ERROR,
                message = "Control transaction failed for magic=${magic.toString(16)} tx=$transactionId cause=${t.javaClass.simpleName}:${t.message ?: "no-message"}",
                cause = t
            )
        } finally {
            pendingRequests.unregister(key, response)
        }
    }

    fun close() {
        shutdown(
            failure = XrControlProtocolException(
                code = XrControlProtocolErrorCode.CONNECTION_CLOSED,
                message = "Control session closed"
            )
        )
    }

    private fun allocateTransactionId(): Int {
        while (true) {
            val current = nextTransactionId.get()
            val next = if (current == Int.MAX_VALUE) 1 else current + 1
            if (nextTransactionId.compareAndSet(current, next)) {
                return current
            }
        }
    }

    private fun runReaderLoop() {
        val failure = try {
            readLoop()
            null
        } catch (t: Throwable) {
            mapReadFailure(t)
        }
        shutdown(failure)
    }

    private fun readLoop() {
        while (!isClosed()) {
            val header = XrControlMessageHeader.readFrom(input)
            val body = input.readExact(header.length)

            if (header.magic == XrControlMagic.KEY_STATE_CHANGE) {
                _inboundMessages.tryEmit(XrControlInboundMessage.KeyStateChangeRaw(body))
                continue
            }

            if (body.size < 4) {
                _inboundMessages.tryEmit(
                    XrControlInboundMessage.Unknown(
                        magic = header.magic,
                        transactionId = null,
                        payload = body
                    )
                )
                continue
            }

            val wireTransactionId = readInt32BigEndian(body, 0)
            val transactionId = XrControlTransactionIds.normalizeInbound(wireTransactionId)
            val payload = body.copyOfRange(4, body.size)
            val key = XrControlPendingKey(transactionId = transactionId, magic = header.magic)

            if (!pendingRequests.resolve(key, payload)) {
                _inboundMessages.tryEmit(
                    XrControlInboundMessage.Unknown(
                        magic = header.magic,
                        transactionId = transactionId,
                        payload = body
                    )
                )
            }
        }
    }

    private fun mapReadFailure(t: Throwable): Throwable {
        if (t is XrControlProtocolException) {
            return t
        }
        if (t is EOFException) {
            return XrControlProtocolException(
                code = XrControlProtocolErrorCode.CONNECTION_CLOSED,
                message = "Control connection closed by remote peer",
                cause = t
            )
        }
        if (t is SocketException && isClosed()) {
            return XrControlProtocolException(
                code = XrControlProtocolErrorCode.CONNECTION_CLOSED,
                message = "Control connection closed",
                cause = t
            )
        }
        return XrControlProtocolException(
            code = XrControlProtocolErrorCode.IO_ERROR,
            message = "Control read loop failed: ${t.javaClass.simpleName}:${t.message ?: "no-message"}",
            cause = t
        )
    }

    private fun shutdown(failure: Throwable?) {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        try {
            socket.close()
        } catch (_: Throwable) {
        }
        val cause = failure
            ?: XrControlProtocolException(
                code = XrControlProtocolErrorCode.CONNECTION_CLOSED,
                message = "Control session closed"
            )
        pendingRequests.failAll(cause)
        sessionScope.cancel()
    }

    companion object {
        fun open(
            socket: Socket,
            info: XrControlSessionInfo,
            defaultRequestTimeoutMs: Long = 1_200L
        ): XrControlSession {
            return XrControlSession(
                socket = socket,
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                info = info,
                defaultRequestTimeoutMs = defaultRequestTimeoutMs
            )
        }
    }
}
