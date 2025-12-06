package com.techphenom.usbipserver.server.protocol.ongoing

import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbIpSubmitUrbReply(seqNum: Int) :
    UsbIpBasicPacket(USBIP_RET_SUBMIT, seqNum, 0, 0, 0) {

    var status = -1
    var actualLength = 0
    var startFrame = 0
    var numberOfPackets = 0xffffffff.toInt()
    var errorCount = 0

    var inData: ByteBuffer = ByteBuffer.allocate(0)
    var isoPacketDescriptors: List<UsbIpIsoPacketDescriptor> = emptyList()

    override fun serializeInternal(): ByteArray {
        val inDataLen = if (inData.capacity() == 0) 0 else actualLength
        val isoDescriptorSize = if (numberOfPackets <= 0) 0 else numberOfPackets * UsbIpIsoPacketDescriptor.WIRE_SIZE
        val totalSize = USBIP_HEADER_SIZE - 20 + inDataLen + isoDescriptorSize

        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(status)
        bb.putInt(actualLength)
        bb.putInt(startFrame)
        bb.putInt(numberOfPackets)
        bb.putInt(errorCount)
        bb.position(USBIP_HEADER_SIZE - 20)

        if (inDataLen > 0) {
            val originalLimit = inData.limit()

            if (inData.remaining() > inDataLen) {
                inData.limit(inData.position() + inDataLen)
            }
            bb.put(inData)

            inData.limit(originalLimit)
        }

        for (descriptor in isoPacketDescriptors) {
            bb.putInt(descriptor.offset)
            bb.putInt(descriptor.length)
            bb.putInt(descriptor.actualLength)
            bb.putInt(descriptor.status)
        }
        return bb.array()
    }

    override fun toString(): String {
        val isoPacketDescriptorsString = isoPacketDescriptors.joinToString(separator = "\n", postfix = ",") { it.toString() }
        return """
            USBIP_RET_SUBMIT
                ${super.toString()},
                Status: $status (0 for successful URB transaction),
                Actual Length: $actualLength,
                Start Frame: $startFrame,
                Number of Packets: $numberOfPackets,
                Error Count: $errorCount
                ISO Packet Descriptors: $isoPacketDescriptorsString
            """
    }
}