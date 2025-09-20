package com.techphenom.usbipserver.server.protocol.utils

fun deviceIdToBusNum(deviceId: Int): Int {
    return deviceId / 1000
}

fun deviceIdToDevNum(deviceId: Int): Int {
    return deviceId % 1000
}

fun devIdToDeviceId(devId: Int): Int {
    // This is the same algorithm as Android uses
    return (devId shr 16 and 0xFF) * 1000 + (devId and 0xFF)
}

fun busIdToBusNum(busId: String): Int {
    return if (busId.indexOf('-') == -1) {
        -1
    } else busId.substring(0, busId.indexOf('-')).toInt()
}

fun busIdToDevNum(busId: String): Int {
    return if (busId.indexOf('-') == -1) {
        -1
    } else busId.substring(busId.indexOf('-') + 1).toInt()
}

fun busIdToDeviceId(busId: String): Int {
    return devIdToDeviceId(busIdToBusNum(busId) shl 16 and 0xFF0000 or busIdToDevNum(busId))
}