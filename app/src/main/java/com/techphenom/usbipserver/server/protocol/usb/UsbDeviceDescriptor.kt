package com.techphenom.usbipserver.server.protocol.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder


class UsbDeviceDescriptor(data: ByteBuffer) {
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
        data.order(ByteOrder.LITTLE_ENDIAN)
        data.position(0)

        bLength = data.get()
        bDescriptorType = data.get()
        bcdUSB = data.getShort()
        bDeviceClass = data.get()
        bDeviceSubClass = data.get()
        bDeviceProtocol = data.get()
        bMaxPacketSize = data.get()
        idVendor = data.getShort()
        idProduct = data.getShort()
        bcdDevice = data.getShort()
        iManufacturer = data.get()
        iProduct = data.get()
        iSerialNumber = data.get()
        bNumConfigurations = data.get()
    }

    companion object {
        const val DESCRIPTOR_SIZE = 18
    }
}
