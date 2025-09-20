package com.techphenom.usbipserver.server.protocol.initial

import android.util.Log
import com.techphenom.usbipserver.server.protocol.ProtocolCodes
import com.techphenom.usbipserver.server.protocol.utils.convertInputStreamToByteArray
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

abstract class CommonPacket {
    var version: Short = 0
    var code: Short = 0
    var status: Int = 0

    constructor(header: ByteArray) {
        val bb = ByteBuffer.wrap(header)

        version = bb.getShort()
        code = bb.getShort()
        status = bb.getInt()
    }

    constructor(version: Short, code: Short, status: Int) {
        this.version = version
        this.code = code
        this.status = status
    }

    protected abstract fun serializeInternal(): ByteArray?

    open fun serialize(): ByteArray {
        val internalData = serializeInternal()
        val internalLen = internalData?.size ?: 0
        val bb = ByteBuffer.allocate(8 + internalLen).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(version)
        bb.putShort(code)
        bb.putInt(status)
        if (internalLen != 0) {
            bb.put(internalData)
        }
        return bb.array()
    }
}

@Throws(IOException::class)
fun convertInputStreamToPacket(incoming: InputStream): CommonPacket? {
    val bb = ByteBuffer.allocate(8)
    convertInputStreamToByteArray(incoming, bb.array())

//    val byteArray: ByteArray = incoming.use { it.readBytes() } Maybe do this in the future refactor

    val usbIpVersion = bb.short // Maybe do a version check here

    val pkt: CommonPacket = when (val code = bb.short) {
        ProtocolCodes.OP_REQ_DEVLIST -> RequestDevListPacket(bb.array())
        ProtocolCodes.OP_REQ_IMPORT -> {
            val pkt = ImportDeviceRequest(bb.array())
            pkt.populateInternal(incoming)
            pkt
        }
        else -> {
            Log.e("UsbIpServerDebug","convertInputStreamToPacket - Unsupported code: $code")
            return null
        }
    }

    return pkt
}