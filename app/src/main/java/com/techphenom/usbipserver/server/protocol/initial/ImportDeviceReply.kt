package com.techphenom.usbipserver.server.protocol.initial

import com.techphenom.usbipserver.server.UsbDeviceInfo
import com.techphenom.usbipserver.server.protocol.ProtocolCodes
import com.techphenom.usbipserver.server.protocol.utils.shortToHex

class ImportDeviceReply: CommonPacket {
    var devInfo: UsbDeviceInfo? = null

    constructor(header: ByteArray): super(header)

    constructor(version: Short):
        super(version, ProtocolCodes.OP_REP_IMPORT, ProtocolCodes.STATUS_OK) {}


    override fun serializeInternal(): ByteArray? {
        if (devInfo == null) {
            return null
        }
        return devInfo?.dev?.serialize()
    }

    override fun toString(): String {
        return """
    OP_REP_IMPORT:
        USB/IP Version: ${shortToHex(version)}
        Reply Code: ${shortToHex(code)}
        Status: $status
        Device Info: $devInfo
        """
    }
}