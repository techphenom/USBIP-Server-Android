package com.techphenom.usbipserver.data

import android.hardware.usb.UsbDevice
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.UsbIpDeviceState

data class UsbDeviceWithState(val device: UsbDevice, var state: UsbIpDeviceState)
