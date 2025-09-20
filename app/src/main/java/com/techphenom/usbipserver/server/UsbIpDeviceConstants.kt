package com.techphenom.usbipserver.server

class UsbIpDeviceConstants {

    enum class UsbIpDeviceState(val description: String) {
        CONNECTED("Connected"),
        CONNECTABLE("Connectable"),
        NOT_CONNECTABLE("Not Connectable")
    }
    companion object {
        const val USB_SPEED_UNKNOWN = 0
        const val USB_SPEED_LOW = 1 // Low Speed (1.5Mbps)
        const val USB_SPEED_FULL = 2 // Full Speed(12Mbps)
        const val USB_SPEED_HIGH = 3 // High Speed(480Mbps)
        const val USB_SPEED_WIRELESS = 4 // Wireless (53.3-480)
        const val USB_SPEED_SUPER = 5 // Super Speed(5000Mbps)

        const val BUS_ID_SIZE = 32
        const val DEV_PATH_SIZE = 256

        const val WIRE_LENGTH = BUS_ID_SIZE + DEV_PATH_SIZE + 24


    }
}