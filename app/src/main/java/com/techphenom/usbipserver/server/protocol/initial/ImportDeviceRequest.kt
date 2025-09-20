package com.techphenom.usbipserver.server.protocol.initial

import com.techphenom.usbipserver.server.UsbIpDeviceConstants
import com.techphenom.usbipserver.server.protocol.utils.convertInputStreamToByteArray
import com.techphenom.usbipserver.server.protocol.utils.shortToHex
import java.io.IOException
import java.io.InputStream

class ImportDeviceRequest(header: ByteArray) : CommonPacket(header) {

    lateinit var busId: String
    
    override fun serializeInternal(): ByteArray {
        throw UnsupportedOperationException("Serialization not supported")
    }

    @Throws(IOException::class)
    fun populateInternal(incoming: InputStream) {
        val bb = ByteArray(UsbIpDeviceConstants.BUS_ID_SIZE)
        convertInputStreamToByteArray(incoming, bb)
        val busIdChars = CharArray(UsbIpDeviceConstants.BUS_ID_SIZE)
        var i = 0
        while (i < bb.size) {
            busIdChars[i] = Char(bb[i].toUShort())
            if (busIdChars[i].code == 0) {
                break
            }
            i++
        }
        busId = String(busIdChars.copyOf(i))
    }

    override fun toString(): String {
        return """
    OP_REQ_IMPORT:
        Version: ${shortToHex(version)}
        Code: ${shortToHex(code)}
        Status: $status (should be 0)
        BusId: $busId
    """
    }
}