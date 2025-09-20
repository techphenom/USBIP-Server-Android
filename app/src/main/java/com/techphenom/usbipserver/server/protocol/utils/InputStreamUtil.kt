package com.techphenom.usbipserver.server.protocol.utils

import java.io.IOException
import java.io.InputStream
import java.io.EOFException

@Throws(IOException::class)
fun convertInputStreamToByteArray(incoming: InputStream, buffer: ByteArray) {
    convertInputStreamToByteArray(incoming, buffer, 0, buffer.size)
}

@Throws(IOException::class)
fun convertInputStreamToByteArray(incoming: InputStream, buffer: ByteArray, offset: Int, length: Int) {
    var bytesRead = 0
    while (bytesRead < length) {
        val ret = incoming.read(buffer, offset + bytesRead, length - bytesRead)
        if (ret == -1) {
            throw EOFException("Premature EOF: Expected $length bytes, but only received $bytesRead bytes before EOF.")
        }
        bytesRead += ret
    }
}