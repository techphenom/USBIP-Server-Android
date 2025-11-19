package com.techphenom.usbipserver.server
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.SparseArray
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
class AttachedDeviceContext {
    lateinit var device: UsbDevice
    lateinit var devConn: UsbDeviceConnection
    var activeConfig: UsbConfiguration? = null
    var activeConfigEndpointCache: SparseArray<UsbEndpoint>? = null
    val activeJobs = mutableMapOf<Int, Job>()
    val activeJobsMutex = Mutex()
    val socketWriteMutex = Mutex()
    val transferSemaphore = Semaphore(permits = MAX_CONCURRENT_TRANSFERS)
    var totalEndpointCount: Int = 0

    companion object {
        const val MAX_CONCURRENT_TRANSFERS = 50
    }
}