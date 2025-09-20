package com.techphenom.usbipserver.server.protocol.initial

import com.techphenom.usbipserver.server.UsbDeviceInfo
import com.techphenom.usbipserver.server.protocol.ProtocolCodes
import com.techphenom.usbipserver.server.protocol.utils.shortToHex
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReplyDevListPacket: CommonPacket {

    var devInfoList: List<UsbDeviceInfo>? = null
    val numberOfExportedDevices: Int
        get() = devInfoList?.size ?: 0

    constructor(header: ByteArray) : super(header)
    constructor(version: Short) :
            super(version, ProtocolCodes.OP_REP_DEVLIST,ProtocolCodes.STATUS_OK) {

    }

    override fun serializeInternal(): ByteArray? {
        var serializedLength = 4

        devInfoList?.forEach { devInfo ->
            serializedLength += devInfo.getWireSize()
        }

        val bb = ByteBuffer.allocate(serializedLength).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(numberOfExportedDevices)

        devInfoList?.forEach { devInfo ->
            bb.put(devInfo.serialize())
        }

        return bb.array()
    }

    override fun toString(): String {
        return """
            OP_REP_DEVLIST:
                USB/IP Version: ${shortToHex(version)}
                Reply Code: ${shortToHex(code)}
                Status: $status
                Number of Devices: $numberOfExportedDevices
                Device List:
                    ${devInfoList?.joinToString("\n")}
        """.trimIndent()
    }
}