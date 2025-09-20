package com.techphenom.usbipserver

import android.hardware.usb.UsbDevice

sealed class UsbIpEvent {
    object OnUpdateNotificationEvent: UsbIpEvent()
    data class DeviceConnectedEvent(val device: UsbDevice): UsbIpEvent()
    data class DeviceDisconnectedEvent(val device: UsbDevice): UsbIpEvent()
}

