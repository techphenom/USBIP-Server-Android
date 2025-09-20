package com.techphenom.usbipserver.server.protocol.usb

import com.techphenom.usbipserver.server.UsbIpDeviceConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbIpDevice {

    var path: String = ""
    var busid: String = ""
    var busnum: Int = 0
    var devnum: Int = 0
    var speed: Int = 0

    var idVendor: Short = 0
    var idProduct: Short = 0
    var bcdDevice: Short = 0x0100.toShort() // USB 1.0 as a safe default

    var bDeviceClass: Byte = 0
    var bDeviceSubClass: Byte = 0
    var bDeviceProtocol: Byte = 0
    var bConfigurationValue: Byte = 0
    var bNumConfigurations: Byte = 0
    var bNumInterfaces: Byte = 0

    private fun stringToWireChars(str: String, size: Int): CharArray {
        val strChars = str.toCharArray()
        return strChars.copyOf(size)
    }

    private fun putChars(bb: ByteBuffer, str: String, size: Int) {
        for (c in stringToWireChars(str, size)) {
            bb.put(c.code.toByte())
        }
    }

    fun serialize(): ByteArray {
        val bb = ByteBuffer.allocate(UsbIpDeviceConstants.WIRE_LENGTH).order(ByteOrder.BIG_ENDIAN)
        putChars(bb, path, UsbIpDeviceConstants.DEV_PATH_SIZE)
        putChars(bb, busid, UsbIpDeviceConstants.BUS_ID_SIZE)
        bb.putInt(busnum)
        bb.putInt(devnum)
        bb.putInt(speed)
        bb.putShort(idVendor)
        bb.putShort(idProduct)
        bb.putShort(bcdDevice)
        bb.put(bDeviceClass)
        bb.put(bDeviceSubClass)
        bb.put(bDeviceProtocol)
        bb.put(bConfigurationValue)
        bb.put(bNumConfigurations)
        bb.put(bNumInterfaces)
        return bb.array()
    }
}