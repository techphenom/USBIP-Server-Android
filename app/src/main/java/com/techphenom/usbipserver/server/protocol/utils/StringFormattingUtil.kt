package com.techphenom.usbipserver.server.protocol.utils

@OptIn(ExperimentalStdlibApi::class)
fun shortToHex(value: Short): String {
    val format = HexFormat {
        upperCase = true
        bytes {
            byteSeparator = " " // One space
            bytePrefix = "0x"
        }
    }
    return value.toInt().toHexString(format)
}

@OptIn(ExperimentalStdlibApi::class)
fun intToHex(value: Int): String {
    val format = HexFormat {
        upperCase = true
        bytes {
            byteSeparator = " " // One space
            bytePrefix = "0x"
        }
    }
    return value.toHexString(format)
}