package com.techphenom.usbipserver.server.protocol.ongoing

import com.techphenom.usbipserver.server.protocol.utils.convertInputStreamToByteArray
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbIpUnlinkUrb(header: ByteArray) : UsbIpBasicPacket(header) {
    
    var seqNumToUnlink = -1

    override fun serializeInternal(): ByteArray {
        throw UnsupportedOperationException("Serializing not supported")
    }

    override fun toString(): String {
        return """
            USBIP_CMD_UNLINK
                Command: $command,
                Sequence Number: $seqNum,
                Dev ID: $devId,
                Direction: $direction (Should be 0),
                Endpoint: $ep (Should be 0),
                Sequence Number to Unlink: $seqNumToUnlink
            """
    }

    companion object {
        private const val WIRE_SIZE = 4

        @Throws(IOException::class)
        fun read(header: ByteArray, incoming: InputStream): UsbIpUnlinkUrb {
            val msg = UsbIpUnlinkUrb(header)
            val continuationHeader = ByteArray(WIRE_SIZE)
            convertInputStreamToByteArray(incoming, continuationHeader)
            val bb = ByteBuffer.wrap(continuationHeader).order(ByteOrder.BIG_ENDIAN)
            msg.seqNumToUnlink = bb.getInt()

            // Finish reading the remaining bytes of the header as padding
            for (i in 0 until USBIP_HEADER_SIZE - (header.size + bb.position())) {
                incoming.read()
            }
            return msg
        }
    }
}