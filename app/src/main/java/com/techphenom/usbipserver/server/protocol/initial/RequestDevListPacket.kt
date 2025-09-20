package com.techphenom.usbipserver.server.protocol.initial

import com.techphenom.usbipserver.server.protocol.utils.shortToHex

class RequestDevListPacket(header: ByteArray) : CommonPacket(header) {

    override fun serializeInternal(): ByteArray {
        throw UnsupportedOperationException("Serialization not supported")
    }

    override fun toString(): String {
        return """
            OP_REQ_DEVLIST:
                USB/IP Version: ${shortToHex(version)}
                Command Code: ${shortToHex(code)}
                Status: $status (unused, shall be set to 0)
        """
    }
}