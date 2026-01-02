package com.techphenom.usbipserver.server
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.SparseArray
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpBasicPacket
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpSubmitUrb
import com.techphenom.usbipserver.server.protocol.utils.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class AttachedDeviceContext {
    lateinit var device: UsbDevice
    lateinit var devConn: UsbDeviceConnection
    var activeConfig: UsbConfiguration? = null
    var activeConfigEndpointCache: SparseArray<UsbEndpoint>? = null
    val pendingTransfers: MutableMap<Int, PendingTransfer> = ConcurrentHashMap()
    val transferSemaphore = Semaphore(permits = MAX_CONCURRENT_TRANSFERS)
    val replyChannel = Channel<UsbIpBasicPacket>(Channel.UNLIMITED)
    private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>()

    companion object {
        const val MAX_CONCURRENT_TRANSFERS = 50
    }

    fun acquireBuffer(size: Int): ByteBuffer {
        val iterator = bufferPool.iterator()
        while (iterator.hasNext()) {
            val buf = iterator.next()
            if (buf.capacity() >= size) {
                iterator.remove()
                buf.clear()
                Logger.i("AttachedDevCon", "Reusing Buffer, capacity: ${buf.capacity()}, position: ${buf.position()}, limit: ${buf.limit()}")
                return buf
            }
        }
        return ByteBuffer.allocateDirect(maxOf(size, 16384))
    }

    fun releaseBuffer(buffer: ByteBuffer?) {
        buffer?.clear()
        if(buffer != null) bufferPool.offer(buffer)
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