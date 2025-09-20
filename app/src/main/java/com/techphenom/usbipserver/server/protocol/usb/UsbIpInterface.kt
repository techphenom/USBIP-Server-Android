package com.techphenom.usbipserver.server.protocol.usb

import com.techphenom.usbipserver.server.protocol.utils.intToHex
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbIpInterface {
    var bInterfaceClass: Byte = 0
    var bInterfaceSubClass: Byte = 0
    var bInterfaceProtocol: Byte = 0


    fun serialize(): ByteArray {
        val bb = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.put(bInterfaceClass)
        bb.put(bInterfaceSubClass)
        bb.put(bInterfaceProtocol)
        // Extra alignment padding of 1 byte
        return bb.array()
    }

    companion object {
        const val WIRE_SIZE: Int = 4
    }

    override fun toString(): String {
        return """
            bInterfaceClass: ${intToHex((bInterfaceClass.toInt() and 0xFF))}
            bInterfaceSubClass: ${intToHex((bInterfaceSubClass.toInt() and 0xFF))} 
            bInterfaceProtocol: ${intToHex((bInterfaceProtocol.toInt() and 0xFF))} 
        """.trimIndent()
    }

}