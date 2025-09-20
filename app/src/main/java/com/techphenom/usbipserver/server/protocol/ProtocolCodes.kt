package com.techphenom.usbipserver.server.protocol

class ProtocolCodes {

    fun decodeOpCode(code: Short): String = when (code) {
        OP_REQ_IMPORT -> "OP_REQ_IMPORT"
        OP_REP_IMPORT -> "OP_REP_IMPORT"
        OP_REQ_DEVLIST -> "OP_REQ_DEVLIST"
        OP_REP_DEVLIST -> "OP_REP_DEVLIST"
        else -> "UNKNOWN"
    }

    fun decodeStatusCode(code: Int): String = when (code) {
        STATUS_OK -> "Request Completed Successfully"
        STATUS_NA -> "Request Failed"
        else -> "UNKNOWN"
    }
    companion object{
        const val USBIP_VERSION: Short = 0x0111;
        const val OP_REQUEST = 0x80 shl 8
        const val OP_REPLY = 0x00 shl 8

        const val STATUS_OK = 0x00
        const val STATUS_NA = 0x01

        const val OP_IMPORT = 0x03
        const val OP_REQ_IMPORT = (OP_REQUEST or OP_IMPORT).toShort()
        const val OP_REP_IMPORT = (OP_REPLY or OP_IMPORT).toShort()

        const val OP_DEVLIST= 0x05
        const val OP_REQ_DEVLIST = (OP_REQUEST or OP_DEVLIST).toShort()
        const val OP_REP_DEVLIST = (OP_REPLY or OP_DEVLIST).toShort()

        const val OP_EXPORT = 0x06
        const val OP_REQ_EXPORT = (OP_REQUEST or OP_EXPORT).toShort()
        const val OP_REP_EXPORT = (OP_REPLY or OP_EXPORT).toShort()

        const val OP_UNEXPORT = 0x07
        const val OP_REQ_UNEXPORT = (OP_REQUEST or OP_UNEXPORT).toShort()
        const val OP_REP_UNEXPORT = (OP_REPLY or OP_UNEXPORT).toShort()
    }
}