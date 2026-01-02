package com.techphenom.usbipserver.server

class UsbIpDeviceConstants {

    enum class UsbIpDeviceState(val description: String) {
        CONNECTED("Connected"),
        CONNECTABLE("Connectable"),
        NOT_CONNECTABLE("Not Connectable")
    }
    enum class LibusbTransferType(val code: Int, val description: String) {
        CONTROL(0, "CONTROL"),
        ISOCHRONOUS(1, "ISO"),
        BULK(2, "BULK"),
        INTERRUPT(3, "INTERRUPT"),
        BULK_STREAM(4, "Bulk stream transfer");

        companion object {
            fun fromCode(code: Int): LibusbTransferType? =
                entries.find { it.code == code }
        }
    }

    companion object {
        const val USB_SPEED_UNKNOWN = 0
        const val USB_SPEED_LOW = 1 // USB 1.0 (1.5Mbps)
        const val USB_SPEED_FULL = 2 // USB 1.1 (12Mbps)
        const val USB_SPEED_HIGH = 3 // USB 2.0 (480Mbps)
        const val USB_SPEED_WIRELESS = 4 // Wireless (53.3-480)
        const val USB_SPEED_SUPER = 5 // USB 3.0 (5000Mbps)

        const val BUS_ID_SIZE = 32
        const val DEV_PATH_SIZE = 256

        const val WIRE_LENGTH = BUS_ID_SIZE + DEV_PATH_SIZE + 24
    }
}