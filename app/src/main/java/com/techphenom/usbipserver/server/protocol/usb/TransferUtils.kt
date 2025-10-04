package com.techphenom.usbipserver.server.protocol.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.techphenom.usbipserver.server.AttachedDeviceContext
import com.techphenom.usbipserver.server.protocol.utils.Logger
import kotlin.math.min
import kotlin.collections.sliceArray

private const val TAG = "TransferUtils"

fun doInterruptTransfer(
    devConn: UsbDeviceConnection,
    endpoint: UsbEndpoint,
    buff: ByteArray,
    timeout: Int
): Int {
    val res = UsbLib().doBulkTransfer(devConn.fileDescriptor, endpoint.address, buff, timeout)
    if (res < 0 && res != -110) { // Don't print for ETIMEDOUT
        Logger.e(TAG,"interruptTransfer failed: $res")
    }
    return res
}

fun doBulkTransfer(
    devConn: UsbDeviceConnection,
    endpoint: UsbEndpoint,
    buff: ByteArray,
    timeout: Int
): Int {
    Logger.i(TAG,"doBulkTransfer - SETUP: ${devConn.fileDescriptor} - ${endpoint.address} - ${buff.size}")
    val res = UsbLib().doBulkTransfer(devConn.fileDescriptor, endpoint.address, buff, timeout)
    if (res < 0  && res != -110) { // Don't print for ETIMEDOUT
        Logger.e(TAG,"bulkTransfer failed: $res")
    }
    return res
}

fun doControlTransfer(
    context: AttachedDeviceContext,
    requestType: Int,
    request: Int,
    value: Int,
    index: Int,
    buff: ByteArray,
    length: Int,
    timeout: Int
): Int {
    // Mask out possible sign expansions
    val _requestType = requestType and 0xFF
    val _request = request and 0xFF
    val _value = value and 0xFFFF
    val _index = index and 0xFFFF
    val _length = length and 0xFFFF
    // We have to handle certain control requests (SET_CONFIGURATION/SET_INTERFACE) by calling
    // Android APIs rather than just submitting the URB directly to the device
    val res = if(!UsbControlHelper.handleInternalControlTransfer(context, _requestType, _request, _value, _index)) {

        Logger.i(TAG,"doControlTransfer - SETUP: ${context.devConn.fileDescriptor} - $_requestType, $_request, $_value, $_index, $_length")

        UsbLib().doControlTransfer(
            context.devConn.fileDescriptor,
            _requestType.toByte(),
            _request.toByte(),
            _value.toShort(),
            _index.toShort(),
            buff,
            _length,
            timeout
        )
    } else 0

    if (res < 0 && res != -110) { // Don't print for ETIMEDOUT
        Logger.e(TAG,"controlTransfer failed: $res")
    }
    return res
}

fun doBulkTransferInChunks(
    devConn: UsbDeviceConnection,
    endpoint: UsbEndpoint,
    buff: ByteArray,
    timeout: Int
): Int {
    val BULK_TRANSFER_CHUNK_SIZE = 16 * 1024 // 16 KB
    var totalBytesTransferred = 0
    var offset = 0
    val totalLength = buff.size
    val isUsbInTransfer = (endpoint.address and UsbConstants.USB_DIR_IN) == UsbConstants.USB_DIR_IN

    while (offset < totalLength) {
        val remaining = totalLength - offset
        val currentChunkSize = min(remaining, BULK_TRANSFER_CHUNK_SIZE)

        val chunkBuffer: ByteArray = if (isUsbInTransfer) {
            ByteArray(currentChunkSize)
        } else {
            // TODO: for higher perf, JNI could take an offset and use the original totalBuffer
            buff.sliceArray(offset until (offset + currentChunkSize))
        }

        val res = UsbLib().doBulkTransfer(
            devConn.fileDescriptor,
            endpoint.address,
            chunkBuffer,
            timeout
        )

        if (res < 0) {
            Logger.e(TAG, "doBulkTransfer: Chunk failed with error $res at offset $offset")
            // TODO: decide whether to return the error immediately, or the totalBytesTransferred so far
            return if (totalBytesTransferred > 0) totalBytesTransferred else res
        }

        if (isUsbInTransfer) {
            if (res > 0) {
                System.arraycopy(chunkBuffer, 0, buff, offset, res)
            }
        }

        totalBytesTransferred += res
        offset += res

        if (isUsbInTransfer && res < currentChunkSize) {
            break //Short IN transfer, assuming end of data from device
        }

        // Usually, a bulk OUT ioctl either sends all or errors. This would be odd.
        if (!isUsbInTransfer && res < currentChunkSize) {
            Logger.e(TAG, "doBulkTransfer: Partial OUT transfer ($res/$currentChunkSize), this is unusual for bulk.")
            break
        }
    }
    return totalBytesTransferred
}