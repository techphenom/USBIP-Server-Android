package com.techphenom.usbipserver.server.protocol.ongoing

import com.techphenom.usbipserver.server.protocol.utils.convertInputStreamToByteArray
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

abstract class UsbIpBasicPacket {

    var command: Int = 0
    var seqNum: Int = 0
    var devId: Int = 0
    var direction: Int = 0
    var ep: Int = 0

    constructor(header: ByteArray) {
        val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        command = bb.getInt()
        seqNum = bb.getInt()
        devId = bb.getInt()
        direction = bb.getInt()
        ep = bb.getInt()
    }

    constructor(command: Int, seqNum: Int, devId: Int, dir: Int, ep: Int) {
        this.command = command
        this.seqNum = seqNum
        this.devId = devId
        this.direction = dir
        this.ep = ep
    }

    protected abstract fun serializeInternal(): ByteArray

    fun serialize(): ByteArray? {
        val internalData = serializeInternal()
        val bb = ByteBuffer.allocate(20 + internalData.size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(command)
        bb.putInt(seqNum)
        bb.putInt(devId)
        bb.putInt(direction)
        bb.putInt(ep)
        bb.put(internalData)
        return bb.array()
    }

    override fun toString(): String {
        return """
            Command: $command,
            Sequence Number: $seqNum,
            Dev ID: $devId,
            Direction: $direction,
            Endpoint: $ep
        """
    }

    companion object {
        const val USBIP_CMD_SUBMIT = 0x0001
        const val USBIP_CMD_UNLINK = 0x0002
        const val USBIP_RET_SUBMIT = 0x0003
        const val USBIP_RET_UNLINK = 0x0004
        const val USBIP_RESET_DEV = 0xFFFF

        const val USBIP_DIR_OUT = 0
        const val USBIP_DIR_IN = 1

        const val USBIP_STATUS_ENDPOINT_HALTED = -32
        const val USBIP_STATUS_URB_ABORTED = -54
        const val USBIP_STATUS_DATA_OVERRUN = -75
        const val USBIP_STATUS_URB_TIMED_OUT = -110
        const val USBIP_STATUS_SHORT_TRANSFER = -121
        const val USBIP_ECONNRESET = -104

        const val USBIP_HEADER_SIZE = 48

        @Throws(IOException::class)
        fun read(incoming: InputStream): UsbIpBasicPacket {
            val bb = ByteBuffer.allocate(20)
            convertInputStreamToByteArray(incoming, bb.array())
            return when (val command = bb.getInt()) {
                USBIP_CMD_SUBMIT -> UsbIpSubmitUrb.read(bb.array(), incoming)
                USBIP_CMD_UNLINK -> UsbIpUnlinkUrb.read(bb.array(), incoming)
                else -> throw IOException("Unknown incoming packet command: $command")
            }
        }
    }

    data class UsbIpIsoPacketDescriptor(
        var offset: Int,
        var length: Int,
        var actualLength: Int,
        var status: Int
    ) {
        override fun toString(): String {
            return "[Offset: $offset, Len: $length, Actual Len: $actualLength, Status: $status]"
        }
        companion object {
            const val WIRE_SIZE = 16
        }
    }
}