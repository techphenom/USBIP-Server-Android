package com.techphenom.usbipserver.server.protocol.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder


class UsbDeviceDescriptor(data: ByteArray) {
    var bLength: Byte
    var bDescriptorType: Byte
    var bcdUSB: Short
    var bDeviceClass: Byte
    var bDeviceSubClass: Byte
    var bDeviceProtocol: Byte
    var bMaxPacketSize: Byte
    var idVendor: Short
    var idProduct: Short
    var bcdDevice: Short
    var iManufacturer: Byte
    var iProduct: Byte
    var iSerialNumber: Byte
    var bNumConfigurations: Byte

    init {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bLength = bb.get()
        bDescriptorType = bb.get()
        bcdUSB = bb.getShort()
        bDeviceClass = bb.get()
        bDeviceSubClass = bb.get()
        bDeviceProtocol = bb.get()
        bMaxPacketSize = bb.get()
        idVendor = bb.getShort()
        idProduct = bb.getShort()
        bcdDevice = bb.getShort()
        iManufacturer = bb.get()
        iProduct = bb.get()
        iSerialNumber = bb.get()
        bNumConfigurations = bb.get()
    }

    companion object {
        const val DESCRIPTOR_SIZE = 18
    }
}
