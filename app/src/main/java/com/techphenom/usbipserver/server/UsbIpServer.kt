package com.techphenom.usbipserver.server

import android.hardware.usb.UsbConstants.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.techphenom.usbipserver.BuildConfig
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
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpSubmitUrbReply
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpUnlinkUrb
import com.techphenom.usbipserver.server.protocol.ongoing.UsbIpUnlinkUrbReply
import com.techphenom.usbipserver.server.protocol.usb.UsbControlHelper
import com.techphenom.usbipserver.server.protocol.usb.UsbDeviceDescriptor
import com.techphenom.usbipserver.server.protocol.usb.UsbIpDevice
import com.techphenom.usbipserver.server.protocol.usb.UsbIpInterface
import com.techphenom.usbipserver.server.protocol.utils.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.UsbIpDeviceState.*
import com.techphenom.usbipserver.server.protocol.usb.UsbLib
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executors

class UsbIpServer(
    private val repository: UsbIpRepository,
    private val usbManager: UsbManager,
    private val onEvent: (event: UsbIpEvent) -> Unit
    ) {

    private lateinit var serverSocket: ServerSocket
    private lateinit var serverScope: CoroutineScope
    private lateinit var interruptDispatcher: ExecutorCoroutineDispatcher
    private lateinit var isoDispatcher: ExecutorCoroutineDispatcher
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
        if(usbLib.init(BuildConfig.DEBUG) < 0) throw IOException("Unable to initialize libusb")

        interruptDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        isoDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        serverScope = CoroutineScope(Dispatchers.IO + exceptionHandler)
        serverScope.launch {
            serverSocket = ServerSocket(USBIP_PORT)

            while (isActive) {
                handleClientConnection(serverSocket.accept(), this)
            }
        }
    }

    fun stop() {
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
        interruptDispatcher.close()
        isoDispatcher.close()
    }

    fun getAttachedDeviceCount(): Int {
        return attachedDevices.size
    }

    private fun handleClientConnection(socket: Socket, scope: CoroutineScope) {
        Logger.i("handleClientConnection", "Client Connected: $socket")

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Logger.e("handleClientConnection()", "$throwable")
        }

        val clientScope = CoroutineScope(scope.coroutineContext + SupervisorJob() + exceptionHandler)
        clientScope.launch {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true

                if(handleInitialRequest(socket)) {
                    while (isActive && handleOngoingRequest(socket, this)) {}
                }
            } finally {
                cleanup(socket)
                try {
                    if (socket.isConnected) socket.close()
                } catch (_: IOException) {} // This is expected if the socket was already closed.
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun handleOngoingRequest(s: Socket, clientJobScope: CoroutineScope): Boolean {
        val inMsg: UsbIpBasicPacket = UsbIpBasicPacket.read(s.getInputStream())
        Logger.i("handleOngoingRequest", "$inMsg")

        val context: AttachedDeviceContext? = attachedDevices[s]
        if (context == null) throw IOException("Client requested non-attached device")
        
        var targetEndpoint: UsbEndpoint? = null
        val epType = if(inMsg.ep == 0) USB_ENDPOINT_XFER_CONTROL
        else {
            val endpoints = context.activeConfigEndpointCache
            if (endpoints != null) {
                val endpointNum = inMsg.ep + (if (inMsg.direction == UsbIpBasicPacket.USBIP_DIR_IN) USB_DIR_IN else 0)
                targetEndpoint = endpoints.get(endpointNum)
                targetEndpoint?.type
            } else null
        }

        val dispatcher = when(epType) {
            USB_ENDPOINT_XFER_INT -> interruptDispatcher
            USB_ENDPOINT_XFER_ISOC -> isoDispatcher
            USB_ENDPOINT_XFER_CONTROL -> clientJobScope.coroutineContext
            else -> Dispatchers.IO
        }

        when (inMsg.command) {
            UsbIpBasicPacket.USBIP_CMD_SUBMIT -> {
                clientJobScope.launch(dispatcher + CoroutineName("URB-${inMsg.seqNum}")) {
                    context.activeJobsMutex.withLock {
                        context.activeJobs[inMsg.seqNum] = this.coroutineContext[Job]!!
                    }
                    try {
                        submitUrbRequest(s, inMsg as UsbIpSubmitUrb, context, epType, targetEndpoint)
                    } finally {
                        context.activeJobsMutex.withLock {
                            context.activeJobs.remove(inMsg.seqNum)
                        }
                    }
                }
            }
            UsbIpBasicPacket.USBIP_CMD_UNLINK -> {
                clientJobScope.launch(Dispatchers.IO + CoroutineName("Unlink-${inMsg.seqNum}")) {
                    abortUrbRequest(s, inMsg as UsbIpUnlinkUrb)
                }
            }
            else -> throw IOException("Unknown incoming packet command: ${inMsg.command}")
        }

        return true
    }

    private fun cleanup(socket: Socket) {
        val context: AttachedDeviceContext = attachedDevices.get(socket) ?: return
        attachedDevices.remove(socket)

        // Release our claim to the interfaces
        for (i in 0 until context.device.interfaceCount) {
            context.devConn.releaseInterface(context.device.getInterface(i))
        }
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
        msg: UsbIpSubmitUrb,
        context: AttachedDeviceContext,
        endpointType: Int?,
        targetEndpoint: UsbEndpoint?
    ) {
        val reply = UsbIpSubmitUrbReply(msg.seqNum,0,0, 0)
        val deviceId: Int = devIdToDeviceId(msg.devId)

        val transferBuff = if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) {
            // For IN, allocate a new buffer and assign it to the reply.
            ByteArray(msg.transferBufferLength).also { reply.inData = it }
        } else {
            msg.outData // For OUT, use the data that came with the request.
        }

        var res: Int
        val seqNum: String = msg.seqNum.toString()
        when (endpointType) {
            null -> {
                Logger.e("submitUrbRequest", "EP not found: " + msg.ep)
                res = ProtocolCodes.STATUS_NA
            }
            USB_ENDPOINT_XFER_CONTROL -> {
                repeat(AttachedDeviceContext.MAX_CONCURRENT_TRANSFERS) {
                    context.transferSemaphore.acquire()
                }
                Logger.i("submitUrbRequest","CONTROL: $seqNum - Started")
                try {
                    res = with(msg.setup) {
                        if(!UsbControlHelper.handleInternalControlTransfer(context, requestType, request, value,index)) {
                            usbLib.doControlTransfer(
                                context.devConn.fileDescriptor,
                                requestType.toByte(),
                                request.toByte(),
                                value.toShort(),
                                index.toShort(),
                                transferBuff,
                                length,
                                300
                            )
                        } else 0
                    }
                } finally {
                    repeat(AttachedDeviceContext.MAX_CONCURRENT_TRANSFERS) {
                        context.transferSemaphore.release()
                    }
                }
                Logger.i("submitUrbRequest", "CONTROL: $seqNum - Complete with $res bytes (wanted ${msg.transferBufferLength})")
            }
            USB_ENDPOINT_XFER_BULK -> {
                context.transferSemaphore.acquire()
                Logger.i("submitUrbRequest", "BULK: $seqNum - ${transferBuff.size} bytes ${if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) "in" else "out"} on EP ${targetEndpoint?.endpointNumber}")
                try {
                    res = usbLib.doBulkTransfer(
                        context.devConn.fileDescriptor,
                        targetEndpoint!!.address,
                        transferBuff,
                        300
                    )
                } finally {
                    context.transferSemaphore.release()
                }
                Logger.i("submitUrbRequest", "BULK: $seqNum - Complete with $res bytes (wanted ${msg.transferBufferLength})")
            }
            USB_ENDPOINT_XFER_INT -> {
                context.transferSemaphore.acquire()
                Logger.i("submitUrbRequest","INTERRUPT: $seqNum - ${msg.transferBufferLength} bytes ${if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) "in" else "out"} on EP ${targetEndpoint?.endpointNumber}")
                try {
                    res = usbLib.doInterruptTransfer(
                        context.devConn.fileDescriptor,
                        targetEndpoint!!.address,
                        transferBuff,
                        100
                    )
                } finally {
                    context.transferSemaphore.release()
                }
                Logger.i("submitUrbRequest", "INTERRUPT: $seqNum - Complete with $res bytes (wanted ${msg.transferBufferLength})")
            }
            USB_ENDPOINT_XFER_ISOC -> {
                context.transferSemaphore.acquire()
                Logger.i("submitUrbRequest", "ISO: ${msg.seqNum} - Started")
                try {
                    val isoPacketLengths = IntArray(msg.numberOfPackets) { i ->
                        msg.isoPacketDescriptors[i].length
                    }
                    res = usbLib.doIsochronousTransfer(
                        context.devConn.fileDescriptor,
                        targetEndpoint!!.address,
                        transferBuff,
                        isoPacketLengths
                    )
                    reply.isoPacketDescriptors = msg.isoPacketDescriptors.toList()
                    reply.numberOfPackets = msg.numberOfPackets
                    reply.startFrame = msg.startFrame
                    for (i in 0 until msg.numberOfPackets) {
                        if (res >= 0){
                            reply.isoPacketDescriptors[i].actualLength = isoPacketLengths[i]
                            reply.isoPacketDescriptors[i].status = 0
                        }
                        else {
                            reply.isoPacketDescriptors[i].actualLength = 0
                            reply.isoPacketDescriptors[i].status = res
                        }
                    }
                } finally {
                    context.transferSemaphore.release()
                }
                Logger.i("submitUrbRequest", "ISO: $seqNum - Complete with $res bytes (wanted ${msg.transferBufferLength})")
            }
            else -> throw IOException("Unsupported endpoint type: $endpointType, seqNum: $seqNum")
        }

        if (res < 0) { // If the request failed, check if the device is still around
            if (getDevice(deviceId) == null) {
                throw IOException("USB device ($deviceId) disappeared during transfer.")
            }
            reply.status = res
        } else {
            reply.actualLength = res
            reply.status = ProtocolCodes.STATUS_OK
        }

        currentCoroutineContext().ensureActive()

        context.socketWriteMutex.withLock {
            Logger.i("submitUrbRequest", "$reply")
            s.getOutputStream().write(reply.serialize())
        }
    }

    private suspend fun abortUrbRequest(s: Socket, msg: UsbIpUnlinkUrb) {
        val context: AttachedDeviceContext = attachedDevices.get(s) ?: return
        var jobToCancel: Job? = null
        for (i in 1..2) {
            context.activeJobsMutex.withLock {
                jobToCancel = context.activeJobs[msg.seqNumToUnlink]
            }
            if (jobToCancel != null) break
            if (i < 2) delay(300)
        }

        if (jobToCancel != null) {
            context.activeJobsMutex.withLock {
                context.activeJobs.remove(msg.seqNumToUnlink)
            }
        }
        jobToCancel?.cancel()

        val reply = UsbIpUnlinkUrbReply(msg.seqNum, msg.devId, msg.direction, msg.ep)
        reply.status = if (jobToCancel != null) UsbIpBasicPacket.USBIP_ECONNRESET else 0
        context.socketWriteMutex.withLock {
            Logger.i("abortUrbRequest", "$reply")
            s.getOutputStream().write(reply.serialize())
        }
    }
}