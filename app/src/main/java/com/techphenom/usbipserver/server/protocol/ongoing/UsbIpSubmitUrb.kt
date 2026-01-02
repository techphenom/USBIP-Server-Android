package com.techphenom.usbipserver.server.protocol.ongoing

import com.techphenom.usbipserver.server.protocol.utils.convertInputStreamToByteArray
import com.techphenom.usbipserver.server.protocol.utils.intToBinary
import com.techphenom.usbipserver.server.protocol.utils.intToHex
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbIpSubmitUrb(header: ByteArray) : UsbIpBasicPacket(header) {
    var transferFlags = UsbIpTransferFlags(0)
    var transferBufferLength = 0
    var startFrame = 0
    var numberOfPackets = 0
    var interval = 0
    var isoPacketDescriptors: List<UsbIpIsoPacketDescriptor> = emptyList()

    lateinit var setup: UsbControlSetup
    var outData: ByteArray = ByteArray(0)

    override fun toString(): String {
        val isoPacketDescriptorsString =  isoPacketDescriptors.joinToString(separator = "\n", postfix = ",") { it.toString() }
        return """
            USBIP_CMD_SUBMIT
                ${super.toString()},
                Transfer Flags: $transferFlags,
                Transfer Buffer Length: $transferBufferLength,
                Start Frame: $startFrame,
                Number of Packets: $numberOfPackets,
                Interval: $interval,
                Setup: ${if(setup.isEmpty()) "[]" else setup.toString()}
                ISO Packet Descriptors: ${if(isoPacketDescriptors.isEmpty()) "[]" else isoPacketDescriptorsString}
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
            msg.transferFlags = UsbIpTransferFlags(bb.getInt())
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

            val totalIsoDescriptorAreaSize = msg.numberOfPackets * UsbIpIsoPacketDescriptor.WIRE_SIZE
            if(totalIsoDescriptorAreaSize > 0) {
                val allIsoDescriptorsBytes = ByteArray(totalIsoDescriptorAreaSize)
                convertInputStreamToByteArray(incoming, allIsoDescriptorsBytes)
                val isoBuff = ByteBuffer.wrap(allIsoDescriptorsBytes).order(ByteOrder.BIG_ENDIAN)
                val descriptors = ArrayList<UsbIpIsoPacketDescriptor>(msg.numberOfPackets)

                repeat(msg.numberOfPackets) {
                    val offset = isoBuff.getInt()
                    val length = isoBuff.getInt()
                    val actualLength = isoBuff.getInt()
                    val status = isoBuff.getInt()
                    descriptors.add(UsbIpIsoPacketDescriptor(offset, length, actualLength, status))
                }
                msg.isoPacketDescriptors = descriptors
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
        private val _bytes: ByteArray


        val requestType: Int get() = _requestType.toInt() and 0xFF
        val request: Int get() = _request.toInt() and 0xFF
        val value: Int get() = _value.toInt() and 0xFFFF
        val index: Int get() = _index.toInt() and 0xFFFF
        val length: Int get() = _length.toInt() and 0xFFFF
        val bytes: ByteArray get() = _bytes

        val reqDirection: Int get() = requestType and 0x80
        val reqType: Int get() = (requestType and 0x60) shr 5
        val reqRecipient: Int get() = requestType and 0x1f

        init {
            require(bytes.size == CONTROL_SETUP_WIRE_SIZE) { "Setup packet must be 8 bytes long" }

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            _requestType = bb.get()
            _request = bb.get()
            _value = bb.getShort()
            _index = bb.getShort()
            _length = bb.getShort()

            _bytes = bytes
        }

        fun isEmpty(): Boolean {
            return requestType == 0 && request == 0 && value == 0 && index == 0 && length == 0
        }

        override fun toString(): String {
            return """ [
                requestType=${intToBinary(requestType)}: ${printRequestType(reqDirection, reqType, reqRecipient)},
                request=${intToHex(request)}: ${printRequest(reqType,request)},
                value=${intToHex(value)},
                index=${intToHex(index)},
                length=$length
                ]"""
        }

        companion object {
            const val CONTROL_SETUP_WIRE_SIZE = 8

            private fun printRequestType(direction: Int, type: Int, recipient: Int ): String {
                val direction = if (direction != 0) "IN" else "OUT"
                val type = when (type) {
                    0 -> "Standard"
                    1 -> "Class"
                    2 -> "Vendor"
                    else -> "Reserved"
                }
                val recipient = when (recipient) {
                    0 -> "Device"
                    1 -> "Interface"
                    2 -> "Endpoint"
                    3 -> "Other"
                    else -> "Reserved"
                }
                return "Direction: $direction, Type: $type, Recipient: $recipient"
            }
            private fun printRequest(type: Int, value: Int): String {
                return when (type) {
                    0 -> printStandardRequest(value) // Standard
                    1 -> printClassRequest(value)    // Class
                    2 -> "Vendor-Specific Request"   // Vendor
                    else -> "Reserved"
                }
            }

            private fun printStandardRequest(value: Int): String {
                return when (value) {
                    0x00 -> "GET_STATUS"
                    0x01 -> "CLEAR_FEATURE"
                    0x03 -> "SET_FEATURE"
                    0x05 -> "SET_ADDRESS"
                    0x06 -> "GET_DESCRIPTOR"
                    0x07 -> "SET_DESCRIPTOR"
                    0x08 -> "GET_CONFIGURATION"
                    0x09 -> "SET_CONFIGURATION"
                    0x0A -> "GET_INTERFACE"
                    0x0B -> "SET_INTERFACE"
                    0x0C -> "SYNCH_FRAME"
                    else -> "Unknown Standard Request"
                }
            }

            private fun printClassRequest(value: Int): String {
                return when (value) {
                    0x01 -> "HID: GET_REPORT"
                    0x09 -> "HID: SET_REPORT"
                    0x0A -> "HID: SET_IDLE"

                    0x00 -> "HUB: GET_STATUS"
                    0x03 -> "HUB: SET_FEATURE"
                    else -> "Unknown Class Request"
                }
            }
        }
    }

    @JvmInline
    value class UsbIpTransferFlags(val value: Int) {
        operator fun contains(flag: Int): Boolean {
            return (value and flag) != 0
        }

        override fun toString(): String {
            val flags = mutableListOf<String>()
            if (value and USBIP_URB_SHORT_NOT_OK != 0) flags.add("SHORT_NOT_OK")
            if (value and USBIP_URB_ISO_ASAP != 0) flags.add("ISO_ASAP")
            if (value and USBIP_URB_NO_TRANSFER_DMA_MAP != 0) flags.add("NO_TRANSFER_DMA_MAP")
            if (value and USBIP_URB_ZERO_PACKET != 0) flags.add("ZERO_PACKET")
            if (value and USBIP_URB_NO_INTERRUPT != 0) flags.add("NO_INTERRUPT")
            if (value and USBIP_URB_FREE_BUFFER != 0) flags.add("FREE_BUFFER")
            if (value and USBIP_URB_DIR_IN != 0) flags.add("DIR_IN") else flags.add("DIR_OUT")

            // Kernel internal memory flags (rarely seen but part of the protocol)
            if (value and USBIP_URB_DMA_MAP_SINGLE != 0) flags.add("DMA_MAP_SINGLE")
            if (value and USBIP_URB_DMA_MAP_PAGE != 0) flags.add("DMA_MAP_PAGE")
            if (value and USBIP_URB_DMA_MAP_SG != 0) flags.add("DMA_MAP_SG")
            if (value and USBIP_URB_MAP_LOCAL != 0) flags.add("MAP_LOCAL")
            if (value and USBIP_URB_SETUP_MAP_SINGLE != 0) flags.add("SETUP_MAP_SINGLE")
            if (value and USBIP_URB_SETUP_MAP_LOCAL != 0) flags.add("SETUP_MAP_LOCAL")
            if (value and USBIP_URB_DMA_SG_COMBINED != 0) flags.add("DMA_SG_COMBINED")

            return if (flags.isEmpty()) "None" else flags.joinToString(" | ")
        }

        companion object {
            const val USBIP_URB_SHORT_NOT_OK = 0x0001
            const val USBIP_URB_ISO_ASAP = 0x0002
            const val USBIP_URB_NO_TRANSFER_DMA_MAP = 0x0004
            const val USBIP_URB_ZERO_PACKET = 0x0040
            const val USBIP_URB_NO_INTERRUPT = 0x0080
            const val USBIP_URB_FREE_BUFFER = 0x0100
            const val USBIP_URB_DIR_IN = 0x0200

            const val USBIP_URB_DMA_MAP_SINGLE = 0x00010000
            const val USBIP_URB_DMA_MAP_PAGE = 0x00020000
            const val USBIP_URB_DMA_MAP_SG = 0x00040000
            const val USBIP_URB_MAP_LOCAL = 0x00080000
            const val USBIP_URB_SETUP_MAP_SINGLE = 0x00100000
            const val USBIP_URB_SETUP_MAP_LOCAL = 0x00200000
            const val USBIP_URB_DMA_SG_COMBINED = 0x00400000
        }
    }
}

