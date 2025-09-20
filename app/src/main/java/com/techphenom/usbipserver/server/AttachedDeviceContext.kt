package com.techphenom.usbipserver.server
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.SparseArray
import kotlinx.coroutines.sync.Mutex
import java.util.HashSet
class AttachedDeviceContext {
    lateinit var device: UsbDevice
    lateinit var devConn: UsbDeviceConnection
    var activeConfiguration: UsbConfiguration? = null
    var activeConfigurationEndpointsByNumDir: SparseArray<UsbEndpoint>? = null// SparseArray()
    var activeMessages: HashSet<Int> = HashSet() // msg.seqNum
    val activeMessagesMutex = Mutex()
    var totalEndpointCount: Int = 0
}