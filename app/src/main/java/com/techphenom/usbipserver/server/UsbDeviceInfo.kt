package com.techphenom.usbipserver.server

import com.techphenom.usbipserver.server.protocol.usb.UsbIpDevice
import com.techphenom.usbipserver.server.protocol.usb.UsbIpInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbDeviceInfo {
    lateinit var dev: UsbIpDevice
    lateinit var interfaces: Array<UsbIpInterface?>

    fun getWireSize(): Int {
        return UsbIpDeviceConstants.WIRE_LENGTH + UsbIpInterface.WIRE_SIZE * dev.bNumInterfaces
    }

    fun serialize(): ByteArray {
        val devSerialized: ByteArray = dev.serialize()
        val bb = ByteBuffer.allocate(getWireSize()).order(ByteOrder.BIG_ENDIAN)
        bb.put(devSerialized)
        for (intrface in interfaces) {
            if (intrface != null) {
                bb.put(intrface.serialize())
            }
        }
        return bb.array()
    }

    override fun toString(): String {
        return """
        path: ${dev.path},
        busnum: ${dev.busnum},
        devnum: ${dev.devnum},
        speed: ${dev.speed},
        idVendor: ${dev.idVendor},
        idProduct: ${dev.idProduct},
        bcdDevice: ${dev.bcdDevice},
        bDeviceClass: ${dev.bDeviceClass},
        bDeviceSubClass: ${dev.bDeviceSubClass}
        bDeviceProtocol: ${dev.bDeviceProtocol},
        bConfigurationValue: ${dev.bConfigurationValue},
        bNumConfigurations: ${dev.bNumConfigurations},
        bNumInterfaces: ${dev.bNumInterfaces},
        Interfaces:
            ${interfaces.joinToString("\n")}
        """.trimIndent()
    }
}