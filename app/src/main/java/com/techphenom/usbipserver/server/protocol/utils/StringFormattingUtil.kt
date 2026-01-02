package com.techphenom.usbipserver.server.protocol.utils

@OptIn(ExperimentalStdlibApi::class)
fun shortToHex(value: Short): String {
    val format = HexFormat {
        upperCase = true
        number {
            prefix = "0x"
        }
    }
    return value.toHexString(format)
}

@OptIn(ExperimentalStdlibApi::class)
fun intToHex(value: Int): String {
    val format = HexFormat {
        upperCase = true
        number {
            prefix = "0x"
        }
    }
    return value.toHexString(format)
}

fun intToBinary(value: Int): String {
    return value.toString(2).padStart(8, '0')
}