package com.achooluv.nikonlink.ptp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny in-process PTP/IP camera used by the loopback tests: real sockets, real
 * framing, no hardware. It answers the same handshake and operations the app
 * performs, so protocol regressions fail in CI instead of on a photo shoot.
 */
class FakeNikonServer(
    private val model: String = "NIKON Z 50II",
    private val objects: Map<Int, FakeObject> = emptyMap(),
    private val acceptEventChannel: Boolean = true,
    private val rejectInit: Boolean = false,
) : Closeable {

    data class FakeObject(
        val name: String,
        val format: Int,
        val body: ByteArray,
        val thumb: ByteArray,
        val captureDate: String = "20261025T093012",
    )

    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    @Volatile
    var closed = false
        private set

    val operationsSeen = mutableListOf<Int>()

    private val thread = thread(isDaemon = true, name = "fake-nikon") { serve() }

    private fun serve() {
        try {
            val command = server.accept()
            val cmdIn = DataInputStream(BufferedInputStream(command.getInputStream()))
            val cmdOut = DataOutputStream(BufferedOutputStream(command.getOutputStream()))

            readPacket(cmdIn) // Init_Command_Request
            if (rejectInit) {
                writePacket(cmdOut, Ptp.PKT_INIT_FAIL, ByteArray(0))
                command.close()
                return
            }
            writePacket(cmdOut, Ptp.PKT_INIT_COMMAND_ACK, initAckPayload(connectionNumber = 7))

            if (acceptEventChannel) {
                val event = server.accept()
                val evtIn = DataInputStream(BufferedInputStream(event.getInputStream()))
                val evtOut = DataOutputStream(BufferedOutputStream(event.getOutputStream()))
                readPacket(evtIn) // Init_Event_Request
                writePacket(evtOut, Ptp.PKT_INIT_EVENT_ACK, le32(7))
            } else {
                // Accept and drop immediately: the client must fall back to a
                // download-only session instead of failing outright.
                server.accept().close()
            }

            while (!closed) {
                val (type, payload) = readPacket(cmdIn)
                if (type != Ptp.PKT_OPERATION_REQUEST) continue
                handleOperation(cmdOut, payload)
            }
        } catch (_: EOFException) {
            // client went away
        } catch (_: Throwable) {
            // ignore: the test asserts on the client side
        }
    }

    private fun handleOperation(out: DataOutputStream, payload: ByteArray) {
        val opcode = Le.u16(payload, 4)
        val txn = Le.u32(payload, 6)
        synchronized(operationsSeen) { operationsSeen += opcode }

        when (opcode) {
            Ptp.OP_OPEN_SESSION, Ptp.OP_CLOSE_SESSION -> respond(out, txn)

            Ptp.OP_GET_DEVICE_INFO -> sendData(out, txn, deviceInfoDataset())

            Ptp.OP_GET_STORAGE_IDS -> sendData(out, txn, uint32Array(intArrayOf(0x00010001)))

            Ptp.OP_GET_STORAGE_INFO -> sendData(out, txn, storageInfoDataset())

            Ptp.OP_GET_OBJECT_HANDLES -> sendData(out, txn, uint32Array(objects.keys.sorted().toIntArray()))

            Ptp.OP_GET_OBJECT_INFO -> {
                val handle = Le.u32(payload, 10)
                val obj = objects[handle] ?: return respond(out, txn, Ptp.RESP_INVALID_OBJECT_HANDLE)
                sendData(out, txn, objectInfoDataset(handle, obj))
            }

            Ptp.OP_GET_THUMB -> {
                val handle = Le.u32(payload, 10)
                val obj = objects[handle] ?: return respond(out, txn, Ptp.RESP_INVALID_OBJECT_HANDLE)
                sendData(out, txn, obj.thumb)
            }

            Ptp.OP_GET_PARTIAL_OBJECT -> {
                val handle = Le.u32(payload, 10)
                val offset = Le.u32(payload, 14).toLong() and 0xFFFFFFFFL
                val maxBytes = Le.u32(payload, 18)
                val obj = objects[handle] ?: return respond(out, txn, Ptp.RESP_INVALID_OBJECT_HANDLE)
                val start = offset.coerceAtMost(obj.body.size.toLong()).toInt()
                val end = minOf(obj.body.size, start + maxBytes)
                val slice = obj.body.copyOfRange(start, end)
                sendData(out, txn, slice, intArrayOf(slice.size))
            }

            else -> respond(out, txn, Ptp.RESP_OPERATION_NOT_SUPPORTED)
        }
    }

    private fun respond(out: DataOutputStream, txn: Int, code: Int = Ptp.RESP_OK, params: IntArray = IntArray(0)) {
        val buf = ByteArrayOutputStream()
        buf.write(le16(code))
        buf.write(le32(txn))
        params.forEach { buf.write(le32(it)) }
        writePacket(out, Ptp.PKT_OPERATION_RESPONSE, buf.toByteArray())
    }

    private fun sendData(
        out: DataOutputStream,
        txn: Int,
        data: ByteArray,
        responseParams: IntArray = IntArray(0),
    ) {
        val start = ByteArrayOutputStream()
        start.write(le32(txn))
        start.write(le64(data.size.toLong()))
        writePacket(out, Ptp.PKT_START_DATA, start.toByteArray())

        val end = ByteArrayOutputStream()
        end.write(le32(txn))
        end.write(data)
        writePacket(out, Ptp.PKT_END_DATA, end.toByteArray())

        respond(out, txn, Ptp.RESP_OK, responseParams)
    }

    private fun initAckPayload(connectionNumber: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(le32(connectionNumber))
        buf.write(ByteArray(16) { it.toByte() })
        buf.write(ptpString("NIKON Z 50II"))
        buf.write(le32(0x00010000))
        return buf.toByteArray()
    }

    private fun deviceInfoDataset(): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(le16(100)) // standard version
        buf.write(le32(0x0000000A)) // vendor extension
        buf.write(le16(100))
        buf.write(ptpString("Nikon"))
        buf.write(le16(0))
        buf.write(uint16Array(intArrayOf(Ptp.OP_GET_DEVICE_INFO, Ptp.OP_GET_PARTIAL_OBJECT)))
        buf.write(uint16Array(IntArray(0)))
        buf.write(uint16Array(IntArray(0)))
        buf.write(uint16Array(IntArray(0)))
        buf.write(uint16Array(intArrayOf(Ptp.FORMAT_JPEG, Ptp.FORMAT_NEF)))
        buf.write(ptpString("Nikon Corporation"))
        buf.write(ptpString(model))
        buf.write(ptpString("1.00"))
        buf.write(ptpString("6001234"))
        return buf.toByteArray()
    }

    private fun storageInfoDataset(): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(le16(0x0004))
        buf.write(le16(0x0002))
        buf.write(le16(0x0000))
        buf.write(le64(64L * 1024 * 1024 * 1024))
        buf.write(le64(32L * 1024 * 1024 * 1024))
        buf.write(le32(1000))
        buf.write(ptpString("SD card"))
        buf.write(ptpString("NIKON"))
        return buf.toByteArray()
    }

    private fun objectInfoDataset(handle: Int, obj: FakeObject): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(le32(0x00010001))
        buf.write(le16(obj.format))
        buf.write(le16(0))
        buf.write(le32(obj.body.size))
        buf.write(le16(Ptp.FORMAT_JPEG))
        buf.write(le32(obj.thumb.size))
        buf.write(le32(160))
        buf.write(le32(120))
        buf.write(le32(5568))
        buf.write(le32(3712))
        buf.write(le32(14))
        buf.write(le32(0))
        buf.write(le16(0))
        buf.write(le32(0))
        buf.write(le32(handle))
        buf.write(ptpString(obj.name))
        buf.write(ptpString(obj.captureDate))
        buf.write(ptpString(obj.captureDate))
        buf.write(ptpString(""))
        return buf.toByteArray()
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val length = readLe32(input)
        val type = readLe32(input)
        val payload = ByteArray(length - 8)
        input.readFully(payload)
        return type to payload
    }

    private fun writePacket(out: DataOutputStream, type: Int, payload: ByteArray) {
        out.write(le32(8 + payload.size))
        out.write(le32(type))
        out.write(payload)
        out.flush()
    }

    private fun readLe32(input: DataInputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return Le.u32(b, 0)
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }

    private fun le16(v: Int): ByteArray = ByteArray(2).also { Le.putU16(it, 0, v) }
    private fun le32(v: Int): ByteArray = ByteArray(4).also { Le.putU32(it, 0, v) }
    private fun le64(v: Long): ByteArray = ByteArray(8).also { Le.putU64(it, 0, v) }

    private fun ptpString(s: String): ByteArray = encodePtpString(s)

    private fun uint16Array(values: IntArray): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(le32(values.size))
        values.forEach { buf.write(le16(it)) }
        return buf.toByteArray()
    }

    private fun uint32Array(values: IntArray): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(le32(values.size))
        values.forEach { buf.write(le32(it)) }
        return buf.toByteArray()
    }

    @Suppress("unused")
    private fun unusedSocket(socket: Socket) = socket.close()
}
