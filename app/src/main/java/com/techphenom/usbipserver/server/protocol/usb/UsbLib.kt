package com.techphenom.usbipserver.server.protocol.usb

import androidx.annotation.Keep
import java.nio.ByteBuffer

class UsbLib {
    init {
        System.loadLibrary("usbipfunctions")
    }
    interface TransferListener {
        fun onTransferCompleted(seqNum: Int, status: Int, actualLength: Int, type: Int, isoPacketActualLengths: IntArray?, isoPacketStatuses: IntArray?)
    }
    private var listener: TransferListener? = null
    fun setListener(listener: TransferListener) {
        this.listener = listener
    }
    @Keep
    private fun onTransferCompleted(seqNum: Int, status: Int, actualLength: Int, type: Int, isoPacketActualLengths: IntArray?, isoPacketStatuses: IntArray?) {
        listener?.onTransferCompleted(seqNum, status, actualLength, type, isoPacketActualLengths, isoPacketStatuses)
    }

    external fun init(): Int
    external fun exit()
    external fun openDeviceHandle(fd: Int): Int
    external fun closeDeviceHandle(fd: Int): Int
    external fun cancelTransfer(seqNum: Int, fd: Int): Int

    external fun doControlTransfer(
        fd: Int,
        requestType: Byte,
        request: Byte,
        value: Short,
        index: Short,
        data: ByteBuffer,
        length: Int,
        timeout: Int
    ): Int

    external fun doControlTransferAsync(
        fd: Int,
        data: ByteBuffer,
        timeout: Int,
        seqNum: Int
    ): Int

    external fun doBulkTransferAsync(
        fd: Int,
        endpoint: Int,
        data: ByteBuffer,
        timeout: Int,
        seqNum: Int
    ): Int

    external fun doInterruptTransferAsync(
        fd: Int,
        endpoint: Int,
        data: ByteBuffer,
        timeout: Int,
        seqNum: Int
    ): Int

    external fun doIsochronousTransferAsync(
        fd: Int,
        endpoint: Int,
        data: ByteBuffer,
        isoPacketLengths: IntArray,
        seqNum: Int
    ): Int

}
