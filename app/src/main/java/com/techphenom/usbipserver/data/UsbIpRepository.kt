package com.techphenom.usbipserver.data

import androidx.lifecycle.LiveData

interface UsbIpRepository {

    fun getServiceStatus(): LiveData<Boolean>

    fun setServiceStatus(status: Boolean)

    fun getUsbDevices(): LiveData<List<UsbDeviceWithState>>

    fun setUsbDevices(devices: List<UsbDeviceWithState>)

    fun updateUsbDevice(device: UsbDeviceWithState)

    fun removeUsbDevice(device: UsbDeviceWithState)

    fun getLocalIpAddress(): String?

}