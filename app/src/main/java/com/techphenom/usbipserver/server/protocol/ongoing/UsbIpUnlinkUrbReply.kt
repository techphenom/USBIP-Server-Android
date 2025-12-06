package com.techphenom.usbipserver.server.protocol.ongoing

import java.nio.ByteBuffer
import java.nio.ByteOrder


class UsbIpUnlinkUrbReply(seqNum: Int) :
    UsbIpBasicPacket(USBIP_RET_UNLINK, seqNum, 0, 0, 0) {

    var status = -1

    override fun serializeInternal(): ByteArray {
        val bb = ByteBuffer.allocate(USBIP_HEADER_SIZE - 20).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(status)
        return bb.array()
    }

    override fun toString(): String {
        return """
            USBIP_RET_UNLINK
                Command: $command,
                Sequence Number: $seqNum,
                Dev ID: $devId (Should be 0),
                Direction: $direction (Should be 0),
                Endpoint: $ep (Should be 0),
                Status: $status
            """
    }
}
