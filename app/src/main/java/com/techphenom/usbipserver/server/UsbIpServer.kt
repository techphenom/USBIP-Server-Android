package com.techphenom.usbipserver.server

import android.hardware.usb.UsbConstants.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.techphenom.usbipserver.UsbIpEvent
import com.techphenom.usbipserver.data.UsbIpRepository
import com.techphenom.usbipserver.server.protocol.ProtocolCodes
import com.techphenom.usbipserver.server.protocol.initial.CommonPacket
import com.techphenom.usbipserver.server.protocol.initial.ImportDeviceReply
import com.techphenom.usbipserver.server.protocol.initial.ImportDeviceRequest
import com.techphenom.usbipserver.server.protocol.initial.ReplyDevListPacket
import com.techphenom.usbipserver.server.protocol.initial.convertInputStreamToPacket
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpBasicPacket
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpSubmitUrb
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpSubmitUrb.UsbControlSetup.Companion.CONTROL_SETUP_WIRE_SIZE
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpSubmitUrbReply
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpUnlinkUrb
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpUnlinkUrbReply
import com.techphenom.usbipserver.server.protocol.usb.UsbControlHelper
import com.techphenom.usbipserver.server.protocol.usb.UsbDeviceDescriptor
import com.techphenom.usbipserver.server.protocol.usb.UsbIpDevice
import com.techphenom.usbipserver.server.protocol.usb.UsbIpInterface
import com.techphenom.usbipserver.server.protocol.utils.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.UsbIpDeviceState.*
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.LibusbTransferType
import com.techphenom.usbipserver.server.protocol.usb.UsbLib
import kotlinx.coroutines.Job
import java.nio.ByteBuffer

class UsbIpServer(
    private val repository: UsbIpRepository,
    private val usbManager: UsbManager,
    private val onEvent: (event: UsbIpEvent) -> Unit
    ) : UsbLib.TransferListener {

    private lateinit var serverSocket: ServerSocket
    private lateinit var serverScope: CoroutineScope
    private var serverShutdown = false
    private val usbLib = UsbLib()
    private val attachedDevices = ConcurrentHashMap<Socket, AttachedDeviceContext>()


    companion object {
        private const val FLAG_POSSIBLE_SPEED_LOW = 0x01
        private const val FLAG_POSSIBLE_SPEED_FULL = 0x02
        private const val FLAG_POSSIBLE_SPEED_HIGH = 0x04
        private const val FLAG_POSSIBLE_SPEED_SUPER = 0x08
        private const val USBIP_PORT = 3240
    }

    fun start() {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Logger.e("start()" , "$throwable")
        }
        serverShutdown = false
        if(usbLib.init() < 0) throw IOException("Unable to initialize libusb")
        usbLib.setListener(this)

        serverScope = CoroutineScope(Dispatchers.IO + exceptionHandler)
        serverScope.launch {
            serverSocket = ServerSocket(USBIP_PORT)

            while (isActive) {
                handleClientConnection(serverSocket.accept(), this)
            }
        }
    }

    fun stop() {
        serverShutdown = true

        if (::serverScope.isInitialized) {
            serverScope.cancel()
        }

        for (socket in attachedDevices.keys) {
            try {
                socket.close()
            } catch (e : IOException) {
                Logger.e("stop", "Error closing socket", e)
            }
        }

        if(::serverSocket.isInitialized && !serverSocket.isClosed) {
            serverSocket.close()
        }
        usbLib.exit()
    }

    fun getAttachedDeviceCount(): Int {
        return attachedDevices.size
    }

    private fun handleClientConnection(socket: Socket, scope: CoroutineScope) {
        Logger.i("handleClientConnection", "Client Connected: $socket")

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Logger.e("handleClientConnection()", "$throwable")
        }

        var writerJob: Job? = null
        val clientScope = CoroutineScope(scope.coroutineContext + SupervisorJob() + exceptionHandler)
        clientScope.launch {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true

                if(handleInitialRequest(socket)) {

                    val context = attachedDevices[socket]
                    if (context == null) {
                        Logger.e("handleClientConnection", "Context missing after handshake!")
                        return@launch
                    }

                    writerJob = launch {
                        try {
                            val output = socket.getOutputStream()
                            for (reply in context.replyChannel) {
                                output.write(reply.serialize())
                                if (reply is UsbIpSubmitUrbReply) {
                                    context.releaseBuffer(reply.inData)
                                }
                            }
                        } catch (e: IOException) {
                            Logger.e("WriterLoop", "Error writing to socket: ${e.message}")
                            socket.close()
                        }
                    }

                    while (isActive && handleOngoingRequest(socket, context)) {}
                }
            } finally {
                writerJob?.cancel()
                cleanup(socket)
                try {
                    if (socket.isConnected) socket.close()
                } catch (_: IOException) {} // This is expected if the socket was already closed.
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun handleOngoingRequest(s: Socket, context: AttachedDeviceContext): Boolean {
        val inMsg: UsbIpBasicPacket = UsbIpBasicPacket.read(s.getInputStream())
        Logger.i("handleOngoingRequest", "$inMsg")

        when (inMsg.command) {
            UsbIpBasicPacket.USBIP_CMD_SUBMIT -> submitUrbRequest(s, inMsg as UsbIpSubmitUrb, context)
            UsbIpBasicPacket.USBIP_CMD_UNLINK -> abortUrbRequest(inMsg as UsbIpUnlinkUrb, context)
            else -> throw IOException("Unknown incoming packet command: ${inMsg.command}")
        }

        return true
    }

    private fun cleanup(socket: Socket) {
        val context: AttachedDeviceContext = attachedDevices[socket] ?: return
        attachedDevices.remove(socket)

        // Release our claim to the interfaces
        for (i in 0 until context.device.interfaceCount) {
            context.devConn.releaseInterface(context.device.getInterface(i))
        }
        if(!serverShutdown) usbLib.closeDeviceHandle(context.devConn.fileDescriptor)
        context.devConn.close()

        val dev = getDevice(context.device.deviceId)
        if(dev != null) onEvent(UsbIpEvent.DeviceDisconnectedEvent(dev))
        onEvent(UsbIpEvent.OnUpdateNotificationEvent)
    }

    @Throws(IOException::class)
    private fun handleInitialRequest(socket: Socket): Boolean {
        val incomingMessage = convertInputStreamToPacket(socket.getInputStream())
        val outgoingMessage: CommonPacket

        if(incomingMessage == null) throw IOException("Incoming packet null")

        var result = false
        Logger.i("handleInitialRequest", "$incomingMessage")

        when(incomingMessage.code) {
            ProtocolCodes.OP_REQ_DEVLIST -> {
                val replyDevListPacket = ReplyDevListPacket(incomingMessage.version)

                replyDevListPacket.devInfoList = repository.getUsbDevices().value
                    ?.filter { it.state == CONNECTABLE || it.state == CONNECTED }
                    ?.map { buildUsbDeviceInfo(it.device) }

                if (replyDevListPacket.devInfoList.isNullOrEmpty()) {
                    replyDevListPacket.status = ProtocolCodes.STATUS_NA
                }
                outgoingMessage = replyDevListPacket
            }
            ProtocolCodes.OP_REQ_IMPORT -> {
                val importRequest: ImportDeviceRequest = incomingMessage as ImportDeviceRequest
                val importReply = ImportDeviceReply(incomingMessage.version)

                val context = attachToDevice(socket, importRequest.busId)
                if (context != null) {
                    importReply.devInfo = getDeviceInfo(importRequest.busId, context)
                    result = importReply.devInfo != null
                }
                importReply.status = if(result) ProtocolCodes.STATUS_OK else ProtocolCodes.STATUS_NA

                outgoingMessage = importReply
            }
            else -> return false
        }

        Logger.i("handleInitialRequest", "$outgoingMessage")
        socket.getOutputStream().write(outgoingMessage.serialize())
        return result
    }

    private fun buildUsbDeviceInfo(device: UsbDevice, context: AttachedDeviceContext? = null): UsbDeviceInfo {
        val info = UsbDeviceInfo()
        val ipDev = UsbIpDevice()

        ipDev.path = device.deviceName
        ipDev.busnum = deviceIdToBusNum(device.deviceId)
        ipDev.devnum = deviceIdToDevNum(device.deviceId)
        ipDev.busid = "${ipDev.busnum}-${ipDev.devnum}"

        ipDev.idVendor = device.vendorId.toShort()
        ipDev.idProduct = device.productId.toShort()
        // ipDev.bcdDevice = -1 use default

        ipDev.bDeviceClass = device.deviceClass.toByte()
        ipDev.bDeviceSubClass = device.deviceSubclass.toByte()
        ipDev.bDeviceProtocol = device.deviceProtocol.toByte()

        ipDev.bConfigurationValue = 0
        ipDev.bNumConfigurations = device.configurationCount.toByte()

        ipDev.bNumInterfaces = device.interfaceCount.toByte()

        info.dev = ipDev
        info.interfaces = arrayOfNulls(ipDev.bNumInterfaces.toInt())

        for (i in 0 until ipDev.bNumInterfaces) {
            val newInterface = UsbIpInterface()
            info.interfaces[i] = newInterface
            val usbInterface: UsbInterface = device.getInterface(i)
            newInterface.bInterfaceClass = usbInterface.interfaceClass.toByte()
            newInterface.bInterfaceSubClass = usbInterface.interfaceSubclass.toByte()
            newInterface.bInterfaceProtocol = usbInterface.interfaceProtocol.toByte()
        }

        var devDesc: UsbDeviceDescriptor? = null
        if (context != null) {
            // Since we're attached already, we can directly query the USB descriptors
            // to fill some information that Android's USB API doesn't expose
            devDesc = UsbControlHelper.readDeviceDescriptor(usbLib, context)
            if (devDesc != null) {
                ipDev.bcdDevice = devDesc.bcdDevice
            }
        }

        ipDev.speed = detectSpeed(device, devDesc)

        return info
    }

    private fun detectSpeed(dev: UsbDevice, devDesc: UsbDeviceDescriptor?): Int {
        var possibleSpeeds: Int = FLAG_POSSIBLE_SPEED_LOW or FLAG_POSSIBLE_SPEED_FULL or
                FLAG_POSSIBLE_SPEED_HIGH or FLAG_POSSIBLE_SPEED_SUPER

        for (i in 0 until dev.interfaceCount) {
            val usbInterface = dev.getInterface(i)
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint: UsbEndpoint = usbInterface.getEndpoint(j)
                if (endpoint.type == USB_ENDPOINT_XFER_BULK || endpoint.type == USB_ENDPOINT_XFER_ISOC) {
                    // Low speed devices can't implement bulk or iso endpoints
                    possibleSpeeds = possibleSpeeds and FLAG_POSSIBLE_SPEED_LOW.inv()
                }
                when(endpoint.type) {
                    USB_ENDPOINT_XFER_CONTROL -> {
                        if (endpoint.maxPacketSize > 8) {
                            // Low speed devices can't use control transfer sizes larger than 8 bytes
                            possibleSpeeds = possibleSpeeds and FLAG_POSSIBLE_SPEED_LOW.inv()
                        }
                        if (endpoint.maxPacketSize < 64) {
                            // High speed devices can't use control transfer sizes smaller than 64 bytes
                            possibleSpeeds = possibleSpeeds and FLAG_POSSIBLE_SPEED_HIGH.inv()
                        }
                        if (endpoint.maxPacketSize < 512) {
                            // Full speed devices can't use control transfer sizes smaller than 512 bytes
                            possibleSpeeds = possibleSpeeds and FLAG_POSSIBLE_SPEED_FULL.inv()
                        }
                    }
                    USB_ENDPOINT_XFER_INT -> {
                        if (endpoint.maxPacketSize > 8) {
                            // Low speed devices can't use interrupt transfer sizes larger than 8 bytes
                            possibleSpeeds = possibleSpeeds and FLAG_POSSIBLE_SPEED_LOW.inv()
                        }
                        if (endpoint.maxPacketSize > 64) {
                            // Full speed devices can't use interrupt transfer sizes larger than 64 bytes
                            possibleSpeeds = possibleSpeeds and FLAG_POSSIBLE_SPEED_FULL.inv()
                        }
                        if (endpoint.maxPacketSize > 512) {
                            // High speed devices can't use interrupt transfer sizes larger than 512 bytes
                            possibleSpeeds = possibleSpeeds and FLAG_POSSIBLE_SPEED_HIGH.inv()
                        }
                    }
                    USB_ENDPOINT_XFER_BULK -> {
                        // A bulk endpoint alone can accurately distinguish between full/high/super speed devices
                        possibleSpeeds = when(endpoint.maxPacketSize) {
                            512 -> FLAG_POSSIBLE_SPEED_HIGH // High speed devices can only use 512 byte bulk transfers
                            1024 -> FLAG_POSSIBLE_SPEED_SUPER // Super speed devices can only use 1024 byte bulk transfers
                            else -> FLAG_POSSIBLE_SPEED_FULL // Otherwise it must be full speed
                        }
                    }
                    USB_ENDPOINT_XFER_ISOC -> {
                        // If the transfer size is 1024, it must be high speed
                        if (endpoint.maxPacketSize == 1024) {
                            possibleSpeeds = FLAG_POSSIBLE_SPEED_HIGH
                        }
                    }
                }
            }
        }
        if (devDesc != null) {
            if (devDesc.bcdUSB < 0x200) {
                // High speed only supported on USB 2.0 or higher
                possibleSpeeds = possibleSpeeds and FLAG_POSSIBLE_SPEED_HIGH.inv()
            }
            if (devDesc.bcdUSB < 0x300) {
                // Super speed only supported on USB 3.0 or higher
                possibleSpeeds = possibleSpeeds and FLAG_POSSIBLE_SPEED_SUPER.inv()
            }
        }
        Logger.i("detectSpeed()","Speed heuristics for device ${dev.deviceId} left us with $possibleSpeeds")

        // Return the lowest speed that we're compatible with
        return if (possibleSpeeds and FLAG_POSSIBLE_SPEED_LOW != 0) {
            UsbIpDeviceConstants.USB_SPEED_LOW
        } else if (possibleSpeeds and FLAG_POSSIBLE_SPEED_FULL != 0) {
            UsbIpDeviceConstants.USB_SPEED_FULL
        } else if (possibleSpeeds and FLAG_POSSIBLE_SPEED_HIGH != 0) {
            UsbIpDeviceConstants.USB_SPEED_HIGH
        } else if (possibleSpeeds and FLAG_POSSIBLE_SPEED_SUPER != 0) {
            UsbIpDeviceConstants.USB_SPEED_SUPER
        } else {
            UsbIpDeviceConstants.USB_SPEED_UNKNOWN // Something went very wrong
        }
    }

    private fun attachToDevice(s: Socket, busId: String): AttachedDeviceContext? {
        val dev: UsbDevice = getDevice(busId) ?: return null
        if (attachedDevices.get(s) != null) return null // Already attached
        val devConn: UsbDeviceConnection = usbManager.openDevice(dev) ?: return null

        // Claim all interfaces since we don't know which one the client wants
        for (i in 0 until dev.interfaceCount) {
            if (!devConn.claimInterface(dev.getInterface(i), true)) {
                Logger.e("attachToDevice()", "Unable to claim interface " + dev.getInterface(i).id)
            }
        }

        val attachedDeviceContext = AttachedDeviceContext()
        attachedDeviceContext.devConn = devConn
        attachedDeviceContext.device = dev

        for (i in 0 until dev.interfaceCount) { // Count all endpoints on all interfaces
            attachedDeviceContext.totalEndpointCount += dev.getInterface(i).endpointCount
        }

        usbLib.openDeviceHandle(devConn.fileDescriptor)
        attachedDevices.put(s, attachedDeviceContext)
        onEvent(UsbIpEvent.OnUpdateNotificationEvent)
        onEvent(UsbIpEvent.DeviceConnectedEvent(dev))
        return attachedDeviceContext
    }

    private fun getDevice(deviceId: Int): UsbDevice? {
        return usbManager.deviceList.values.find { it.deviceId == deviceId }
    }
    private fun getDevice(busId: String): UsbDevice? {
        return getDevice(busIdToDeviceId(busId))
    }
    private fun getDeviceInfo(busId: String, context: AttachedDeviceContext): UsbDeviceInfo? {
        val dev: UsbDevice = getDevice(busId) ?: return null
        return buildUsbDeviceInfo(dev, context)
    }

    private suspend fun submitUrbRequest(
        s: Socket,
        inMsg: UsbIpSubmitUrb,
        context: AttachedDeviceContext
    ) {
        var epAddress = 0
        val epType = if(inMsg.ep == 0) USB_ENDPOINT_XFER_CONTROL
        else {
            val endpoints = context.activeConfigEndpointCache
            if (endpoints != null) {
                val endpointNum = inMsg.ep + (if (inMsg.direction == UsbIpBasicPacket.USBIP_DIR_IN) USB_DIR_IN else 0)
                val targetEndpoint = endpoints.get(endpointNum)
                if (targetEndpoint != null) {
                    epAddress = targetEndpoint.address
                    targetEndpoint.type
                } else null
            } else null
        }

        val isoPacketLengths = IntArray(inMsg.numberOfPackets) { i ->
            inMsg.isoPacketDescriptors[i].length
        }

        if(epType == USB_ENDPOINT_XFER_CONTROL){
            repeat(AttachedDeviceContext.MAX_CONCURRENT_TRANSFERS) {
                context.transferSemaphore.acquire()
            }
        } else context.transferSemaphore.acquire()

        var totalBufferLength = inMsg.transferBufferLength
        if(epType == USB_ENDPOINT_XFER_CONTROL) totalBufferLength += CONTROL_SETUP_WIRE_SIZE
        val transferBuffer = context.acquireBuffer(totalBufferLength)
        transferBuffer.limit(totalBufferLength)

        if (inMsg.direction == UsbIpBasicPacket.USBIP_DIR_OUT) {
            transferBuffer.put(inMsg.outData)
            transferBuffer.position(0)
        }
        context.pendingTransfers[inMsg.seqNum] = AttachedDeviceContext.PendingTransfer(s, inMsg, transferBuffer)

        var submitRes: Int
        val seqNum: String = inMsg.seqNum.toString()
        when (epType) {
            USB_ENDPOINT_XFER_CONTROL -> {
                Logger.i("submitUrbRequest","CONTROL: $seqNum - Started")
                with(inMsg.setup) {
                    if(UsbControlHelper.handleTransferInternally(requestType, request)) {
                        UsbControlHelper.doInternalControlTransfer(context, requestType, request, value,index)
                        onTransferCompleted(inMsg.seqNum, ProtocolCodes.STATUS_OK, 0, LibusbTransferType.CONTROL.code, isoPacketLengths, null)
                        return
                    } else {
                        transferBuffer.clear()
                        transferBuffer.limit(totalBufferLength)
                        transferBuffer.put(bytes)
                        if (inMsg.direction == UsbIpBasicPacket.USBIP_DIR_OUT && length > 0) {
                            transferBuffer.put(inMsg.outData)
                        }
                        transferBuffer.position(0)
                        submitRes = usbLib.doControlTransferAsync(
                            context.devConn.fileDescriptor,
                            transferBuffer.slice(),
                            300,
                            inMsg.seqNum
                        )
                    }
                }
            }
            USB_ENDPOINT_XFER_BULK -> {
                Logger.i("submitUrbRequest", "BULK: $seqNum - ${inMsg.transferBufferLength} bytes ${if (inMsg.direction == UsbIpBasicPacket.USBIP_DIR_IN) "in" else "out"}")
                submitRes = usbLib.doBulkTransferAsync(
                    context.devConn.fileDescriptor,
                    epAddress,
                    transferBuffer.slice(),
                    300,
                    inMsg.seqNum
                )
            }
            USB_ENDPOINT_XFER_INT -> {
                Logger.i("submitUrbRequest","INTERRUPT: $seqNum - ${inMsg.transferBufferLength} bytes ${if (inMsg.direction == UsbIpBasicPacket.USBIP_DIR_IN) "in" else "out"}")
                submitRes = usbLib.doInterruptTransferAsync(
                    context.devConn.fileDescriptor,
                    epAddress,
                    transferBuffer.slice(),
                    100,
                    inMsg.seqNum
                )
            }
            USB_ENDPOINT_XFER_ISOC -> {
                Logger.i("submitUrbRequest", "ISO: ${inMsg.seqNum} - Started")
                submitRes = usbLib.doIsochronousTransferAsync(
                    context.devConn.fileDescriptor,
                    epAddress,
                    transferBuffer.slice(),
                    isoPacketLengths,
                    inMsg.seqNum
                )
            }
            else -> throw IOException("Unsupported endpoint type: $epType, seqNum: $seqNum")
        }

        if (submitRes < 0) {
            Logger.e("submitUrbRequest", "Submission failed with $submitRes")
            context.pendingTransfers.remove(inMsg.seqNum)

            if (epType == USB_ENDPOINT_XFER_CONTROL) {
                repeat(AttachedDeviceContext.MAX_CONCURRENT_TRANSFERS) {
                    context.transferSemaphore.release()
                }
            } else {
                context.transferSemaphore.release()
            }

            sendReply(context, inMsg, submitRes, transferBuffer, 0, isoPacketLengths, null)
        }
    }

    private fun abortUrbRequest(msg: UsbIpUnlinkUrb, context: AttachedDeviceContext) {
        val pending = context.pendingTransfers.remove(msg.seqNumToUnlink)
        var wasCancelled = false
        if (pending != null) {
            wasCancelled = usbLib.cancelTransfer(msg.seqNumToUnlink, context.devConn.fileDescriptor) == 0
            context.transferSemaphore.release()
        }

        val reply = UsbIpUnlinkUrbReply(msg.seqNum)
        reply.status = if (wasCancelled) UsbIpBasicPacket.USBIP_ECONNRESET else 0
        Logger.i("abortUrbRequest", "$reply")
        context.replyChannel.trySend(reply)
    }

    override fun onTransferCompleted(seqNum: Int, status: Int, actualLength: Int, type: Int, isoPacketActualLengths: IntArray?, isoPacketStatuses: IntArray?) {
        val transferType = LibusbTransferType.fromCode(type)
        Logger.i("onTransferCompleted", "${transferType?.description}: $seqNum - Complete with $actualLength bytes (status: $status)")

        for (context in attachedDevices.values) {
            val pending = context.pendingTransfers.remove(seqNum)
            if (pending != null) {
                if (transferType == LibusbTransferType.CONTROL && actualLength > 0) {
                    pending.transferBuffer.position(8) // Skip CONTROL Transfer 8-byte header
                } else {
                    pending.transferBuffer.position(0) // Ensure buffer at starting position
                }
                if(transferType == LibusbTransferType.CONTROL){
                    repeat(AttachedDeviceContext.MAX_CONCURRENT_TRANSFERS) {
                        context.transferSemaphore.release()
                    }
                } else context.transferSemaphore.release()

                with(pending){
                    sendReply(context, request, status, transferBuffer, actualLength, isoPacketActualLengths, isoPacketStatuses)
                }
                return
            }
        }
        Logger.i("onTransferCompleted", "Orphaned callback - seqNum: $seqNum (status: $status)")
    }

    private fun sendReply(
        context: AttachedDeviceContext,
        request: UsbIpSubmitUrb,
        status: Int,
        transferBuffer: ByteBuffer,
        actualLength: Int,
        isoPacketActualLengths: IntArray?,
        isoPacketStatuses: IntArray?
    ) {
        val validIsoLengths = isoPacketActualLengths ?: IntArray(request.numberOfPackets)
        val validIsoStatuses = isoPacketStatuses ?: IntArray(request.numberOfPackets)

        val reply = UsbIpSubmitUrbReply(request.seqNum)
        reply.status = status
        reply.actualLength = actualLength
        reply.inData = if(request.direction == UsbIpBasicPacket.USBIP_DIR_IN) transferBuffer
        else {
            context.releaseBuffer(transferBuffer)
            null
        }

        reply.isoPacketDescriptors = request.isoPacketDescriptors.toList()
        reply.numberOfPackets = request.numberOfPackets
        reply.startFrame = request.startFrame
        for (i in 0 until request.numberOfPackets) {
            reply.isoPacketDescriptors[i].actualLength = validIsoLengths[i]
            reply.isoPacketDescriptors[i].status = validIsoStatuses[i]
            if(validIsoStatuses[i] < 0) reply.errorCount++
        }
        reply.errorCount = if(status < 0 && request.numberOfPackets == 0) 1 else 0

        Logger.i("submitUrbRequest", "$reply")
        context.replyChannel.trySend(reply)
    }
}