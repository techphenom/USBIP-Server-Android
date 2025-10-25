package com.techphenom.usbipserver.server.protocol.usb


class UsbLib {
    init {
        System.loadLibrary("usbipfunctions")
    }

    external fun init(): Int
    external fun exit()

    external fun doControlTransfer(
        fd: Int,
        requestType: Byte,
        request: Byte,
        value: Short,
        index: Short,
        data: ByteArray,
        length: Int,
        timeout: Int
    ): Int

    external fun doBulkTransfer(
        fd: Int,
        endpoint: Int,
        data: ByteArray?,
        timeout: Int
    ): Int

    external fun doInterruptTransfer(
        fd: Int,
        endpoint: Int,
        data: ByteArray?,
        timeout: Int
    ): Int

    external fun doIsochronousTransfer(
        fd: Int,
        endpoint: Int,
        data: ByteArray?,
        isoPacketLengths: IntArray
    ): Int

}
