package com.techphenom.usbipserver.server

import android.hardware.usb.UsbConstants.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.techphenom.usbipserver.UsbIpEvent
import com.techphenom.usbipserver.UsbIpService.Companion.attachedDevices
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
import com.techphenom.usbipserver.server.protocol.usb.doBulkTransferInChunks
import com.techphenom.usbipserver.server.protocol.usb.doControlTransfer
import com.techphenom.usbipserver.server.protocol.usb.doInterruptTransfer
import com.techphenom.usbipserver.server.protocol.utils.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class UsbIpServer(
    private val repository: UsbIpRepository,
    private val usbManager: UsbManager,
    private val onEvent: (event: UsbIpEvent) -> Unit
    ) {

    private val runningJobs = ConcurrentHashMap<Socket, Job>()
    private var socketMap = HashMap<Socket, AttachedDeviceContext>()
    private lateinit var serverSocket: ServerSocket
    private lateinit var serverScope: CoroutineScope

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
        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
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

        for ((socket, _) in runningJobs) {
            try {
                socket.close()
            } catch (e : IOException) {
                Logger.e("stop", "Error closing socket", e)
            }
        }
        runningJobs.clear()

        if(::serverSocket.isInitialized && !serverSocket.isClosed) {
            serverSocket.close()
        }
    }

    private fun handleClientConnection(socket: Socket, scope: CoroutineScope) {
        Logger.i("handleClientConnection", "Client Connected: $socket")

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Logger.e("handleClientConnection()", "$throwable")
            if(throwable is IOException) {
                cleanupSocket(socket)
                runningJobs.remove(socket)
            }
        }

        val clientJob = scope.launch(exceptionHandler) {
            socket.tcpNoDelay = true
            socket.keepAlive = true

            if(handleInitialRequest(socket)) {
                while (isActive && handleOngoingRequest(socket, this)) {}
            }
        }
        runningJobs[socket] = clientJob

        clientJob.invokeOnCompletion {
            runningJobs.remove(socket)
            try {
                if (socket.isConnected) socket.close()
            } catch (e: IOException) {
                Logger.e("invokeOnCompletion", "Error closing socket on job completion", e)
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun handleOngoingRequest(s: Socket, clientJobScope: CoroutineScope): Boolean {
        val inMsg: UsbIpBasicPacket? = UsbIpBasicPacket.read(s.getInputStream())

        Logger.i("handleOngoingRequest", "$inMsg")

        when (inMsg?.command) {
            UsbIpBasicPacket.USBIP_CMD_SUBMIT -> {
                clientJobScope.launch(Dispatchers.IO + CoroutineName("URB-${inMsg.seqNum}")) {
                    submitUrbRequest(s, inMsg as UsbIpSubmitUrb)
                }
            }
            UsbIpBasicPacket.USBIP_CMD_UNLINK -> abortUrbRequest(s, inMsg as UsbIpUnlinkUrb)
            else -> {
                Logger.e("handleOngoingRequest", "Unknown Command: ${inMsg?.command}")
                return false
            }
        }

        return true
    }

    private fun cleanupSocket(socket: Socket) {
        val context: AttachedDeviceContext = socketMap.remove(socket) ?: return
        cleanupDetachedDevice(context.device.deviceId)
    }

    private fun cleanupDetachedDevice(deviceId: Int) {
        val context: AttachedDeviceContext = attachedDevices.get(deviceId) ?: return

        attachedDevices.remove(deviceId)

        // Release our claim to the interfaces
        for (i in 0 until context.device.interfaceCount) {
            context.devConn.releaseInterface(context.device.getInterface(i))
        }

        context.devConn.close()

        onEvent(UsbIpEvent.OnUpdateNotificationEvent)
        onEvent(UsbIpEvent.DeviceDisconnectedEvent(context.device))
    }

    @Throws(IOException::class)
    private fun handleInitialRequest(socket: Socket): Boolean {
        val incomingMessage = convertInputStreamToPacket(socket.getInputStream())
        val outgoingMessage: CommonPacket

        if(incomingMessage == null) {
            socket.close()
            Logger.e("handleInitialRequest", "Incoming packet null")
            return false
        }
        var result = false
        Logger.i("handleInitialRequest", "$incomingMessage")

        when(incomingMessage.code) {
            ProtocolCodes.OP_REQ_DEVLIST -> {
                val replyDevListPacket = ReplyDevListPacket(incomingMessage.version)
                val connectableDevices = repository.getUsbDevices().value?.filter { dev ->
                    dev.state == UsbIpDeviceConstants.UsbIpDeviceState.CONNECTABLE ||
                            dev.state == UsbIpDeviceConstants.UsbIpDeviceState.CONNECTED
                }
                val devices = connectableDevices?.map {
                    it.device
                }
                replyDevListPacket.devInfoList = devices?.let { convertDevices(it) }
                if (replyDevListPacket.devInfoList == null) {
                    replyDevListPacket.status = ProtocolCodes.STATUS_NA
                }
                outgoingMessage = replyDevListPacket
            }
            ProtocolCodes.OP_REQ_IMPORT -> {
                val importRequest: ImportDeviceRequest = incomingMessage as ImportDeviceRequest
                val importReply = ImportDeviceReply(incomingMessage.version)

                result = attachToDevice(socket, importRequest.busId)
                if (result) {
                    importReply.devInfo = getDeviceByBusId(importRequest.busId)
                    if(importReply.devInfo == null) {
                        result = false
                    }
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

    private fun convertDevices(devices: List<UsbDevice>): List<UsbDeviceInfo> {
        val result = mutableListOf<UsbDeviceInfo>()
        for (device in devices) {
            val devInfo = buildUsbDeviceInfo(device)
            result.add(devInfo)
        }
        return result
    }

    private fun buildUsbDeviceInfo(device: UsbDevice): UsbDeviceInfo {
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
            info.interfaces[i] = UsbIpInterface()
            val iface: UsbInterface = device.getInterface(i)
            info.interfaces[i]!!.bInterfaceClass = iface.interfaceClass.toByte()
            info.interfaces[i]!!.bInterfaceSubClass = iface.interfaceSubclass.toByte()
            info.interfaces[i]!!.bInterfaceProtocol = iface.interfaceProtocol.toByte()
        }

        val context: AttachedDeviceContext? = attachedDevices.get(device.deviceId)
        var devDesc: UsbDeviceDescriptor? = null
        if (context != null) {
            // Since we're attached already, we can directly query the USB descriptors
            // to fill some information that Android's USB API doesn't expose
            devDesc = UsbControlHelper.readDeviceDescriptor(context)
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
            val iface = dev.getInterface(i)
            for (j in 0 until iface.endpointCount) {
                val endpoint: UsbEndpoint = iface.getEndpoint(j)
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
            UsbIpDeviceConstants.USB_SPEED_UNKNOWN // Something went very wrong in speed detection
        }
    }

    private fun attachToDevice(s: Socket, busId: String): Boolean {
        val dev: UsbDevice = getDevice(busId) ?: return false
        if (attachedDevices.get(dev.deviceId) != null) {
            return false // Already attached
        }

        val devConn: UsbDeviceConnection = usbManager.openDevice(dev) ?: return false

        // Claim all interfaces since we don't know which one the client wants
        for (i in 0 until dev.interfaceCount) {
            if (!devConn.claimInterface(dev.getInterface(i), true)) {
                Logger.e("attachToDevice()", "Unable to claim interface " + dev.getInterface(i).id)
            }
        }

        // Create a context for this attachment
        val attachedDeviceContext = AttachedDeviceContext()
        attachedDeviceContext.devConn = devConn
        attachedDeviceContext.device = dev

        var endpointCount = 0
        for (i in 0 until dev.interfaceCount) { // Count all endpoints on all interfaces
            endpointCount += dev.getInterface(i).endpointCount
        }

        attachedDeviceContext.totalEndpointCount = endpointCount

        attachedDevices.put(dev.deviceId, attachedDeviceContext)
        socketMap[s] = attachedDeviceContext
        onEvent(UsbIpEvent.OnUpdateNotificationEvent)
        onEvent(UsbIpEvent.DeviceConnectedEvent(dev))
        return true
    }

    private fun getDevice(deviceId: Int): UsbDevice? {
        for (dev in usbManager.deviceList.values) {
            if (dev.deviceId == deviceId) {
                return dev
            }
        }
        return null
    }
    private fun getDevice(busId: String): UsbDevice? {
        return getDevice(busIdToDeviceId(busId))
    }
    private fun getDeviceByBusId(busId: String): UsbDeviceInfo? {
        val dev: UsbDevice = getDevice(busId) ?: return null
        return buildUsbDeviceInfo(dev)
    }

    private suspend fun submitUrbRequest(s: Socket, msg: UsbIpSubmitUrb) {
        val reply = UsbIpSubmitUrbReply(msg.seqNum,0,0, 0)
        val deviceId: Int = devIdToDeviceId(msg.devId)
        val context: AttachedDeviceContext? = attachedDevices.get(deviceId)

        if (context == null) { // This should never happen, but kill the connection if it does
            killClient(s)
            return
        }

        var targetEndpoint: UsbEndpoint? = null
        val endpointType =
            if(msg.ep == 0) USB_ENDPOINT_XFER_CONTROL
            else {
                if (context.activeConfigurationEndpointsByNumDir != null) {
                    val endptNumDir = msg.ep + (if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) USB_DIR_IN else 0)
                    targetEndpoint = context.activeConfigurationEndpointsByNumDir!!.get(endptNumDir)
                    targetEndpoint?.type
                } else null
            }

        context.activeMessagesMutex.withLock { // This message is now active
            context.activeMessages.add(msg.seqNum)
        }

        val buff: ByteBuffer = if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) {
            ByteBuffer.allocate(msg.transferBufferLength) // The buffer is allocated by us
        } else {
            ByteBuffer.wrap(msg.outData) // The buffer came in with the request
        }
        if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) {
            reply.inData = buff.array() // We need to store our buffer in the URB reply
        }

        var res: Int
        when (endpointType) {
            null -> {
                Logger.e("submitUrbRequest", "EP not found: " + msg.ep)
                res = ProtocolCodes.STATUS_NA
            }
            USB_ENDPOINT_XFER_CONTROL -> {
                // This is little endian
                val bb = ByteBuffer.wrap(msg.setup).order(ByteOrder.LITTLE_ENDIAN)
                val requestType = bb.get()
                val request = bb.get()
                val value = bb.getShort()
                val index = bb.getShort()
                val length = bb.getShort()

                res = doControlTransfer(
                    context,
                    requestType.toInt(),
                    request.toInt(),
                    value.toInt(),
                    index.toInt(),
                    buff.array(),
                    length.toInt(),
                    300
                )
                Logger.i("submitUrbRequest", "control transfer result code: $res")
            }
            USB_ENDPOINT_XFER_BULK -> {
                Logger.i("USB_ENDPOINT_XFER_BULK", "Bulk Transfer ${buff.array().size} bytes ${if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) "in" else "out"} on EP ${targetEndpoint?.endpointNumber}")
                res = doBulkTransferInChunks(
                    context.devConn,
                    targetEndpoint!!,
                    buff.array(),
                    300
                )
                Logger.i("USB_ENDPOINT_XFER_BULK", "Bulk Transfer complete with $res bytes (wanted ${msg.transferBufferLength})")
            }
            USB_ENDPOINT_XFER_INT -> {
                Logger.i("USB_ENDPOINT_XFER_INT","Interrupt transfer ${msg.transferBufferLength} bytes ${if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) "in" else "out"} on EP ${targetEndpoint?.endpointNumber}")
                res = doInterruptTransfer(
                    context.devConn,
                    targetEndpoint!!,
                    buff.array(),
                    100
                )
                Logger.i("USB_ENDPOINT_XFER_INT", "Interrupt transfer complete with $res bytes (wanted ${msg.transferBufferLength})")
            }
            else -> {
                Logger.e("submitUrb", "Unsupported endpoint type: $endpointType")
                context.activeMessagesMutex.withLock {
                    context.activeMessages.remove(msg.seqNum)
                }
                killClient(s)
                return
            }
        }

        if (!currentCoroutineContext().isActive) return // Job cancelled

        if (res < 0) { // If the request failed, check if the device is still around
            if (getDevice(deviceId) == null) {
                killClient(s) // The device is gone, so terminate the client
                return
            }
            reply.status = res
        } else {
            reply.actualLength = res
            reply.status = ProtocolCodes.STATUS_OK
        }

        context.activeMessagesMutex.withLock {
            if(!context.activeMessages.remove(msg.seqNum)) return
        }

        //Logger.i("sendReply", "$reply")
        sendReply(s, reply)
    }

    private suspend fun abortUrbRequest(s: Socket, msg: UsbIpUnlinkUrb) {
        val deviceId: Int = devIdToDeviceId(msg.devId)
        val context: AttachedDeviceContext = attachedDevices.get(deviceId) ?: return
        var found = false
        context.activeMessagesMutex.withLock {
            if(context.activeMessages.remove(msg.seqNumToUnlink)){
                found = true
            }
        }
        val reply = UsbIpUnlinkUrbReply(msg.seqNum, msg.devId, msg.direction, msg.ep)
        reply.status = if (found) UsbIpBasicPacket.USBIP_ECONNRESET else 0
        sendReply(s, reply)
    }

    private fun sendReply(s: Socket, reply: UsbIpBasicPacket) {
        Logger.i("sendReply", "$reply")
        try { // We need to synchronize to avoid writing on top of ourselves
            synchronized(s) { s.getOutputStream().write(reply.serialize()) }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun killClient(socket: Socket) {
        val job: Job? = runningJobs.remove(socket)
        socket.close()
        job?.cancel()
    }
}

