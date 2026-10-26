package com.achooluv.nikonlink.ptp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.locks.ReentrantLock
import javax.net.SocketFactory
import kotlin.concurrent.withLock

/**
 * A PTP/IP client for Wi-Fi Nikon cameras.
 *
 * The transport is two TCP connections to port 15740: one command/data
 * connection that carries PTP transactions, and one event connection that the
 * camera pushes ObjectAdded/CaptureComplete events on. All transactions are
 * serialised; payloads are streamed so a 500 MB movie never lands in heap.
 */
class PtpIpClient(private val friendlyName: String) : Closeable {

    private companion object {
        /** Camera firmware drops connections that talk immediately after SYN-ACK. */
        const val POST_CONNECT_DELAY_MS = 350L
    }

    /** Fixed initiator GUID that Nikon's own app announces, so the body treats us as a known host. */
    private val guid: ByteArray = byteArrayOf(
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
        0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
    )

    private var cmdSocket: Socket? = null
    private var cmdOut: java.io.OutputStream? = null
    private var evtSocket: Socket? = null
    private var cmdInput: LeInput? = null

    private val txnLock = ReentrantLock()
    private var txnId = 0
    private var sessionId = 0

    @Volatile private var closed = true
    @Volatile private var eventThread: Thread? = null

    @Volatile var onEvent: ((PtpEvent) -> Unit)? = null
    @Volatile var onDisconnected: ((Throwable) -> Unit)? = null

    /** Transport-level hook used by the app to bind sockets to the camera's Wi-Fi network. */
    var socketFactory: SocketFactory = SocketFactory.getDefault()

    val isConnected: Boolean get() = !closed && cmdSocket != null

    /** False when the body refused the event channel; downloads still work, events do not. */
    @Volatile
    var eventsAvailable: Boolean = false
        private set

    /**
     * Dials [host]:[port], performs the PTP/IP handshake on both connections and
     * opens a PTP session. Blocking; call from a background dispatcher.
     */
    fun connect(host: String, port: Int = Ptp.PORT, connectTimeoutMs: Int = 12_000) {
        closeQuietly()

        val command = newSocket()
        command.connect(InetSocketAddress(host, port), connectTimeoutMs)
        // Measured on a Z 50II: the body drops the connection when the first
        // PTP/IP packet follows the TCP handshake immediately. A short pause
        // makes Init_Command_Request reliably answered.
        settleAfterConnect()
        val ack = runCatching { initCommand(command) }.getOrElse { e ->
            runCatching { command.close() }
            throw PtpLinkException("Handshake failed: ${e.message}", e)
        }

        val event = newSocket()
        event.connect(InetSocketAddress(host, port), connectTimeoutMs)
        settleAfterConnect()
        // A few bodies answer the command channel but never bind the event channel.
        // Downloads still work in that case, so treat a failed event handshake as a
        // degraded mode instead of a fatal error.
        runCatching { event.soTimeout = 8_000 }
        val eventReady = runCatching { initEvent(event, ack.connectionNumber) }
            .onFailure { runCatching { event.close() } }
            .isSuccess
        if (eventReady) runCatching { event.soTimeout = 0 }

        cmdSocket = command
        cmdOut = command.getOutputStream()
        cmdInput = LeInput(command.getInputStream())
        evtSocket = if (eventReady) event else null
        eventsAvailable = eventReady
        closed = false
        txnId = 0
        sessionId = 0

        if (eventReady) {
            eventThread = Thread({ readEventLoop(event) }, "nikonlink-events").apply {
                isDaemon = true
                start()
            }
        }

        try {
            openSession(1)
        } catch (e: PtpException) {
            if (e.code != Ptp.RESP_SESSION_ALREADY_OPEN) {
                closeQuietly()
                throw e
            }
            sessionId = 1
        }
    }

    private fun newSocket(): Socket = socketFactory.createSocket().apply {
        tcpNoDelay = true
        keepAlive = true
        soTimeout = 45_000
        runCatching { receiveBufferSize = 4 shl 20 }
    }

    /** Pause between the TCP handshake and the first PTP/IP packet (see [connect]). */
    private fun settleAfterConnect() {
        runCatching { Thread.sleep(POST_CONNECT_DELAY_MS) }
    }

    private class InitAck(val connectionNumber: Int)

    private fun initCommand(socket: Socket): InitAck {
        val out = socket.getOutputStream()
        val input = LeInput(socket.getInputStream())
        val payload = ByteArray(16 + friendlyName.length * 2 + 2 + 4)
        System.arraycopy(guid, 0, payload, 0, 16)
        val nameBytes = encodeUtf16z(friendlyName)
        System.arraycopy(nameBytes, 0, payload, 16, nameBytes.size)
        Le.putU32(payload, 16 + nameBytes.size, 0x00010000)
        Frame.writePacket(out, Ptp.PKT_INIT_COMMAND_REQUEST, payload)

        val (type, len) = Frame.readHeader(input)
        if (type == Ptp.PKT_INIT_FAIL) throw PtpLinkException("Camera refused the connection (InitFail)")
        if (type != Ptp.PKT_INIT_COMMAND_ACK) throw PtpLinkException("Unexpected handshake packet $type")
        val buf = ByteArray(len)
        input.readFully(buf, 0, len)
        val connNumber = Le.u32(buf, 0)
        return InitAck(connNumber)
    }

    private fun initEvent(socket: Socket, connectionNumber: Int) {
        val out = socket.getOutputStream()
        val input = LeInput(socket.getInputStream())
        val payload = ByteArray(4)
        Le.putU32(payload, 0, connectionNumber)
        Frame.writePacket(out, Ptp.PKT_INIT_EVENT_REQUEST, payload)

        val (type, len) = Frame.readHeader(input)
        if (type == Ptp.PKT_INIT_FAIL) throw PtpLinkException("Camera refused the event channel")
        if (type != Ptp.PKT_INIT_EVENT_ACK) throw PtpLinkException("Unexpected event handshake $type")
        input.skip(len.toLong())
    }

    private fun readEventLoop(socket: Socket) {
        val input = LeInput(socket.getInputStream())
        val out = runCatching { socket.getOutputStream() }.getOrNull()
        try {
            while (!closed) {
                val (type, len) = Frame.readHeader(input)
                when (type) {
                    Ptp.PKT_EVENT -> {
                        val buf = ByteArray(len)
                        input.readFully(buf, 0, len)
                        val event = decodeEvent(buf)
                        onEvent?.invoke(event)
                    }
                    Ptp.PKT_PROBE_REQUEST -> {
                        out?.let { Frame.writePacket(it, Ptp.PKT_PROBE_RESPONSE, ByteArray(0)) }
                    }
                    else -> input.skip(len.toLong())
                }
            }
        } catch (_: Throwable) {
            // fall through to disconnect handling
        } finally {
            if (!closed) {
                markDead(PtpLinkException("Camera connection lost"))
            }
        }
    }

    private fun decodeEvent(buf: ByteArray): PtpEvent {
        val code = Le.u16(buf, 0)
        val txn = Le.u32(buf, 2)
        val params = ArrayList<Int>((buf.size - 6) / 4)
        var off = 6
        while (off + 4 <= buf.size) {
            params += Le.u32(buf, off)
            off += 4
        }
        return PtpEvent(code, txn, params.toIntArray())
    }

    /** Marks the link dead and notifies the owner exactly once per connection. */
    private fun markDead(cause: Throwable) {
        val wasOpen = !closed
        closed = true
        runCatching { cmdSocket?.close() }
        runCatching { evtSocket?.close() }
        cmdSocket = null
        evtSocket = null
        cmdInput = null
        cmdOut = null
        if (wasOpen) onDisconnected?.invoke(cause)
    }

    // ---------------------------------------------------------------- sessions

    fun openSession(id: Int = 1) {
        transaction(Ptp.OP_OPEN_SESSION, intArrayOf(id))
        sessionId = id
    }

    private fun closeSession() {
        runCatching { transaction(Ptp.OP_CLOSE_SESSION, IntArray(0)) }
    }

    override fun close() {
        if (!closed) {
            runCatching { closeSession() }
        }
        markDead(PtpLinkException("Closed by app"))
        eventThread?.interrupt()
        eventThread = null
    }

    private fun closeQuietly() {
        closed = true
        cmdSocket?.let { runCatching { it.close() } }
        evtSocket?.let { runCatching { it.close() } }
        cmdSocket = null
        evtSocket = null
        cmdInput = null
        cmdOut = null
    }

    // ------------------------------------------------------------ transactions

    /**
     * Runs one PTP transaction. [dataOut] is an optional host-to-device payload;
     * [sink] receives the device-to-host data phase as (input, byteCount).
     * Returns the response parameters.
     */
    private fun transaction(
        operation: Int,
        params: IntArray,
        dataOut: ByteArray? = null,
        sink: ((LeInput, Int) -> Unit)? = null,
    ): IntArray {
        txnLock.withLock {
            return try {
                executeLocked(operation, params, dataOut, sink)
            } catch (e: PtpException) {
                if (operation != Ptp.OP_OPEN_SESSION && e.code == Ptp.RESP_SESSION_NOT_OPEN) {
                    // Camera forgot the session after an idle drop: reopen and retry once.
                    val sid = if (sessionId == 0) 1 else sessionId
                    try {
                        executeLocked(Ptp.OP_OPEN_SESSION, intArrayOf(sid), null, null)
                        sessionId = sid
                        executeLocked(operation, params, dataOut, sink)
                    } catch (retry: Throwable) {
                        if (retry is java.io.IOException && retry !is PtpException) markDead(retry)
                        throw retry
                    }
                } else {
                    throw e
                }
            }
        }
    }

    private fun executeLocked(
        operation: Int,
        params: IntArray,
        dataOut: ByteArray?,
        sink: ((LeInput, Int) -> Unit)?,
    ): IntArray {
        val socket = cmdSocket ?: throw PtpLinkException("Not connected")
        val out = cmdOut ?: throw PtpLinkException("Not connected")
        val input = cmdInput ?: throw PtpLinkException("Not connected")
        if (closed) throw PtpLinkException("Not connected")

        txnId = if (txnId == Int.MAX_VALUE) 1 else txnId + 1
        val txn = txnId

        val reqPayload = ByteArray(4 + 2 + 4 + params.size * 4)
        Le.putU32(reqPayload, 0, if (dataOut != null) 2 else 1)
        Le.putU16(reqPayload, 4, operation)
        Le.putU32(reqPayload, 6, txn)
        params.forEachIndexed { i, p -> Le.putU32(reqPayload, 10 + i * 4, p) }

        try {
            Frame.writePacket(out, Ptp.PKT_OPERATION_REQUEST, reqPayload)

            if (dataOut != null) {
                val start = ByteArray(12)
                Le.putU32(start, 0, txn)
                Le.putU64(start, 4, dataOut.size.toLong())
                Frame.writePacket(out, Ptp.PKT_START_DATA, start)
                val end = ByteArray(4 + dataOut.size)
                Le.putU32(end, 0, txn)
                System.arraycopy(dataOut, 0, end, 4, dataOut.size)
                Frame.writePacket(out, Ptp.PKT_END_DATA, end)
            }

            while (true) {
                val (type, len) = Frame.readHeader(input)
                when (type) {
                    Ptp.PKT_START_DATA -> input.skip(len.toLong())
                    Ptp.PKT_DATA, Ptp.PKT_END_DATA -> {
                        val payloadTxn = input.u32()
                        val dataLen = len - 4
                        if (payloadTxn != txn) {
                            input.skip(dataLen.toLong())
                        } else if (sink != null) {
                            sink(input, dataLen)
                        } else {
                            input.skip(dataLen.toLong())
                        }
                    }
                    Ptp.PKT_OPERATION_RESPONSE -> {
                        val buf = ByteArray(len)
                        input.readFully(buf, 0, len)
                        val code = Le.u16(buf, 0)
                        val respParams = ArrayList<Int>()
                        var off = 6
                        while (off + 4 <= buf.size) {
                            respParams += Le.u32(buf, off)
                            off += 4
                        }
                        if (code != Ptp.RESP_OK) throw PtpException(operation, code)
                        return respParams.toIntArray()
                    }
                    Ptp.PKT_PROBE_REQUEST -> Frame.writePacket(out, Ptp.PKT_PROBE_RESPONSE, ByteArray(0))
                    else -> input.skip(len.toLong())
                }
            }
        } catch (e: java.io.IOException) {
            if (e !is PtpException && e !is PtpLinkException) markDead(e)
            throw e
        }
    }

    // -------------------------------------------------------------- operations

    fun deviceInfo(): DeviceInfo = Datasets.deviceInfo(collect(Ptp.OP_GET_DEVICE_INFO, IntArray(0)))

    fun storageIds(): IntArray = Datasets.uint32Array(collect(Ptp.OP_GET_STORAGE_IDS, IntArray(0)))

    fun storageInfo(storageId: Int): StorageInfo =
        Datasets.storageInfo(storageId, collect(Ptp.OP_GET_STORAGE_INFO, intArrayOf(storageId)))

    fun objectHandles(storageId: Int, format: Int = Ptp.ALL_FORMATS, parent: Int = Ptp.ALL_HANDLES): IntArray =
        Datasets.uint32Array(collect(Ptp.OP_GET_OBJECT_HANDLES, intArrayOf(storageId, format, parent)))

    fun objectInfo(handle: Int): ObjectInfo =
        Datasets.objectInfo(collect(Ptp.OP_GET_OBJECT_INFO, intArrayOf(handle)))

    fun thumbnail(handle: Int): ByteArray = collect(Ptp.OP_GET_THUMB, intArrayOf(handle))

    /** Nikon 640px preview; bodies that reject the vendor op throw a [PtpException]. */
    fun largeThumbnail(handle: Int): ByteArray = collect(Ptp.OP_NIKON_GET_LARGE_THUMB, intArrayOf(handle))

    fun batteryLevel(): Int {
        val raw = collect(Ptp.OP_GET_DEVICE_PROP_VALUE, intArrayOf(Ptp.PROP_BATTERY_LEVEL))
        return if (raw.isEmpty()) 0 else raw[0].toInt() and 0xFF
    }

    fun transferList(): IntArray = Datasets.uint32Array(collect(Ptp.OP_NIKON_GET_TRANSFER_LIST, IntArray(0)))

    fun setTransferListLock(lock: Boolean) {
        transaction(
            Ptp.OP_NIKON_SET_TRANSFER_LIST_LOCK,
            intArrayOf(if (lock) 3 else 0),
        )
    }

    /**
     * Streams [length] bytes of object [handle] starting at [offset] into [sink].
     * Returns the byte count actually written (camera-reported when available).
     */
    fun downloadRange(handle: Int, offset: Long, length: Int, sink: (LeInput, Int) -> Unit): Long {
        var written = 0L
        val params = intArrayOf(handle, offset.toInt(), length)
        val resp = transaction(Ptp.OP_GET_PARTIAL_OBJECT, params, null) { input, count ->
            sink(input, count)
            written += count
        }
        val reported = if (resp.isNotEmpty()) (resp[0].toLong() and 0xFFFFFFFFL) else written
        return if (reported > 0) reported else written
    }

    /** Streams the whole object with GetObject (no resume support). Returns bytes written. */
    fun downloadObject(handle: Int, sink: (LeInput, Int) -> Unit): Long {
        var written = 0L
        transaction(Ptp.OP_GET_OBJECT, intArrayOf(handle), null) { input, count ->
            sink(input, count)
            written += count
        }
        return written
    }

    /**
     * Streams the first [maxBytes] of an object. EXIF sits at the head of JPEG/NEF,
     * so this reads camera/lens metadata without pulling a whole RAW.
     */
    fun readHead(handle: Int, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(maxBytes)
        transaction(Ptp.OP_GET_PARTIAL_OBJECT, intArrayOf(handle, 0, maxBytes), null) { input, count ->
            val buffer = ByteArray(count)
            input.readFully(buffer, 0, count)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /** Cheap round-trip that keeps the idle link warm. */
    fun keepAlive() {
        storageIds()
    }

    private fun collect(operation: Int, params: IntArray): ByteArray {
        val buffer = ByteArrayOutputStream(512)
        transaction(operation, params, null) { input, count ->
            val chunk = ByteArray(count)
            input.readFully(chunk, 0, count)
            buffer.write(chunk)
        }
        return buffer.toByteArray()
    }
}

/** A PTP event delivered on the event channel. */
data class PtpEvent(val code: Int, val transactionId: Int, val params: IntArray) {
    override fun equals(other: Any?): Boolean =
        other is PtpEvent && other.code == code && other.transactionId == transactionId &&
            other.params.contentEquals(params)

    override fun hashCode(): Int = 31 * code + transactionId + params.contentHashCode()
}
