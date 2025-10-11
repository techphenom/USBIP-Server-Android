package com.techphenom.usbipserver.server.protocol.ongoing

import com.techphenom.usbipserver.server.protocol.utils.convertInputStreamToByteArray
import com.techphenom.usbipserver.server.protocol.utils.intToHex
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbIpSubmitUrb(header: ByteArray) : UsbIpBasicPacket(header) {
    var transferFlags = 0
    var transferBufferLength = 0
    var startFrame = 0
    var numberOfPackets = 0
    var interval = 0

    lateinit var setup: UsbControlSetup
    var outData: ByteArray = ByteArray(0)

    override fun toString(): String {
        return """
            USBIP_CMD_SUBMIT
                ${super.toString()},
                Transfer Flags: ${intToHex(transferFlags)},
                Transfer Buffer Length: $transferBufferLength,
                Start Frame: $startFrame,
                Number of Packets: $numberOfPackets,
                Interval: $interval,
                Setup: $setup
            """
    }

    override fun serializeInternal(): ByteArray {
        throw UnsupportedOperationException("Serializing not supported")
    }

    companion object {
        private const val WIRE_SIZE = 20 + 8

        @Throws(IOException::class)
        fun read(header: ByteArray, incoming: InputStream): UsbIpSubmitUrb {
            val msg = UsbIpSubmitUrb(header)
            val continuationHeader = ByteArray(WIRE_SIZE)
            convertInputStreamToByteArray(incoming, continuationHeader)
            val bb = ByteBuffer.wrap(continuationHeader).order(ByteOrder.BIG_ENDIAN)
            msg.transferFlags = bb.getInt()
            msg.transferBufferLength = bb.getInt()
            msg.startFrame = bb.getInt()
            msg.numberOfPackets = bb.getInt()
            msg.interval = bb.getInt()

            val bytes = ByteArray(8)
            bb.get(bytes)
            msg.setup = UsbControlSetup(bytes)

            if (msg.direction == USBIP_DIR_OUT) {
                msg.outData = ByteArray(msg.transferBufferLength)
                convertInputStreamToByteArray(incoming, msg.outData)
            }

            // read the iso_packet_descriptor even tho we don't use for now
            val totalIsoDescriptorAreaSize = msg.numberOfPackets * 16
            if(totalIsoDescriptorAreaSize > 0) {
                val allIsoDescriptorsBytes = ByteArray(totalIsoDescriptorAreaSize)
                convertInputStreamToByteArray(incoming, allIsoDescriptorsBytes)
            }

            return msg
        }
    }

    class UsbControlSetup(bytes: ByteArray) {
        private val _requestType: Byte
        private val _request: Byte
        private val _value: Short
        private val _index: Short
        private val _length: Short

        val requestType: Int get() = _requestType.toInt() and 0xFF
        val request: Int get() = _request.toInt() and 0xFF
        val value: Int get() = _value.toInt() and 0xFFFF
        val index: Int get() = _index.toInt() and 0xFFFF
        val length: Int get() = _length.toInt() and 0xFFFF

        init {
            require(bytes.size == 8) { "Setup packet must be 8 bytes long" }

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            _requestType = bb.get()
            _request = bb.get()
            _value = bb.getShort()
            _index = bb.getShort()
            _length = bb.getShort()
        }

        override fun toString(): String {
            return """ [
                requestType=${intToHex(requestType)},
                request=${intToHex(request)},
                value=${intToHex(value)},
                index=${intToHex(index)},
                length=$length
                ]"""
        }
    }
}

