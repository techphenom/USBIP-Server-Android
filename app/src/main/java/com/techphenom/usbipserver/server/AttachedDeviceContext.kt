package com.techphenom.usbipserver.server
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.SparseArray
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpBasicPacket
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpSubmitUrb
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class AttachedDeviceContext {
    lateinit var device: UsbDevice
    lateinit var devConn: UsbDeviceConnection
    var activeConfig: UsbConfiguration? = null
    var activeConfigEndpointCache: SparseArray<UsbEndpoint>? = null
    val pendingTransfers: MutableMap<Int, PendingTransfer> = ConcurrentHashMap()
    val transferSemaphore = Semaphore(permits = MAX_CONCURRENT_TRANSFERS)
    var totalEndpointCount: Int = 0
    val replyChannel = Channel<UsbIpBasicPacket>(Channel.UNLIMITED)

    companion object {
        const val MAX_CONCURRENT_TRANSFERS = 50
    }

    data class PendingTransfer(
        val socket: Socket,
        val request: UsbIpSubmitUrb,
        var transferBuffer: ByteBuffer
    ) {
        fun updateBuffer(data: ByteBuffer) {
            transferBuffer = data
        }
    }
}