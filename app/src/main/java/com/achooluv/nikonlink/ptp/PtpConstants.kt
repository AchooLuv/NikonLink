package com.achooluv.nikonlink.ptp

/**
 * PTP / PTP-IP wire constants used by NikonLink.
 *
 * Reference: ISO 15740 (PTP) and the PTP-IP transport spec, plus the Nikon
 * vendor operations documented in the open-source AeroShutter project (MIT).
 */
object Ptp {
    const val PORT = 15740

    // ---- PTP/IP packet types -------------------------------------------------
    const val PKT_INIT_COMMAND_REQUEST = 1
    const val PKT_INIT_COMMAND_ACK = 2
    const val PKT_INIT_EVENT_REQUEST = 3
    const val PKT_INIT_EVENT_ACK = 4
    const val PKT_INIT_FAIL = 5
    const val PKT_OPERATION_REQUEST = 6
    const val PKT_OPERATION_RESPONSE = 7
    const val PKT_EVENT = 8
    const val PKT_START_DATA = 9
    const val PKT_DATA = 10
    const val PKT_CANCEL_TRANSACTION = 11
    const val PKT_END_DATA = 12
    const val PKT_PROBE_REQUEST = 13
    const val PKT_PROBE_RESPONSE = 14

    const val HEADER_SIZE = 8

    // ---- Operation codes -----------------------------------------------------
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMB = 0x100A
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_GET_PARTIAL_OBJECT = 0x101B

    /** Nikon vendor op: larger (~640px) JPEG preview. Optional per body. */
    const val OP_NIKON_GET_LARGE_THUMB = 0x90C4

    /** Nikon vendor op: lock/unlock the "send to smart device" queue. */
    const val OP_NIKON_SET_TRANSFER_LIST_LOCK = 0x9407

    /** Nikon vendor op: read the "send to smart device" handle list. */
    const val OP_NIKON_GET_TRANSFER_LIST = 0x9408

    // ---- Response codes ------------------------------------------------------
    const val RESP_OK = 0x2001
    const val RESP_GENERAL_ERROR = 0x2002
    const val RESP_SESSION_NOT_OPEN = 0x2003
    const val RESP_OPERATION_NOT_SUPPORTED = 0x2005
    const val RESP_INCOMPLETE_TRANSFER = 0x2007
    const val RESP_INVALID_STORAGE_ID = 0x2008
    const val RESP_INVALID_OBJECT_HANDLE = 0x2009
    const val RESP_ACCESS_DENIED = 0x200F
    const val RESP_DEVICE_BUSY = 0x2019
    const val RESP_INVALID_PARAMETER = 0x201D
    const val RESP_SESSION_ALREADY_OPEN = 0x201E

    // ---- Events --------------------------------------------------------------
    const val EVENT_OBJECT_ADDED = 0x4002
    const val EVENT_OBJECT_REMOVED = 0x4003
    const val EVENT_STORE_ADDED = 0x4004
    const val EVENT_STORE_REMOVED = 0x4005
    const val EVENT_CAPTURE_COMPLETE = 0x400D

    // ---- Object formats ------------------------------------------------------
    const val FORMAT_ASSOCIATION = 0x3001
    const val FORMAT_UNDEFINED = 0x3000
    const val FORMAT_NEF = 0xB101
    const val FORMAT_JPEG = 0x3801
    const val FORMAT_MOV = 0x300D

    // ---- Device properties ---------------------------------------------------
    const val PROP_BATTERY_LEVEL = 0x5001

    // ---- Storage selectors ---------------------------------------------------
    const val ALL_STORAGES = 0xFFFFFFFF.toInt()
    const val ALL_FORMATS = 0
    const val ALL_HANDLES = 0
}

/** Thrown when the camera answers a request with a non-OK PTP response code. */
class PtpException(
    val operation: Int,
    val code: Int,
    message: String = "PTP ${opName(operation)} failed: ${responseName(code)}",
) : java.io.IOException(message)

/** Thrown when the PTP/IP link itself fails (socket closed, framing error...). */
class PtpLinkException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

internal fun opName(op: Int): String = "0x%04X".format(op)

internal fun responseName(code: Int): String = when (code) {
    Ptp.RESP_OK -> "OK"
    Ptp.RESP_GENERAL_ERROR -> "GeneralError"
    Ptp.RESP_SESSION_NOT_OPEN -> "SessionNotOpen"
    Ptp.RESP_OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
    Ptp.RESP_INCOMPLETE_TRANSFER -> "IncompleteTransfer"
    Ptp.RESP_INVALID_STORAGE_ID -> "InvalidStorageID"
    Ptp.RESP_INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
    Ptp.RESP_ACCESS_DENIED -> "AccessDenied"
    Ptp.RESP_DEVICE_BUSY -> "DeviceBusy"
    Ptp.RESP_INVALID_PARAMETER -> "InvalidParameter"
    Ptp.RESP_SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
    else -> "ResponseCode(0x%04X)".format(code)
}
