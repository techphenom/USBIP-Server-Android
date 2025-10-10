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
import kotlinx.coroutines.SupervisorJob
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
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.UsbIpDeviceState.*
import kotlinx.coroutines.ensureActive

class UsbIpServer(
    private val repository: UsbIpRepository,
    private val usbManager: UsbManager,
    private val onEvent: (event: UsbIpEvent) -> Unit
    ) {

    private lateinit var serverSocket: ServerSocket
    private lateinit var serverScope: CoroutineScope
    val attachedDevices = ConcurrentHashMap<Socket, AttachedDeviceContext>()

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
            info.interfaces[i] = UsbIpInterface()
            val iface: UsbInterface = device.getInterface(i)
            info.interfaces[i]!!.bInterfaceClass = iface.interfaceClass.toByte()
            info.interfaces[i]!!.bInterfaceSubClass = iface.interfaceSubclass.toByte()
            info.interfaces[i]!!.bInterfaceProtocol = iface.interfaceProtocol.toByte()
        }

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

    private suspend fun submitUrbRequest(s: Socket, msg: UsbIpSubmitUrb) {
        val reply = UsbIpSubmitUrbReply(msg.seqNum,0,0, 0)
        val deviceId: Int = devIdToDeviceId(msg.devId)
        val context: AttachedDeviceContext? = attachedDevices.get(s)

        if (context == null) throw IOException("Client requested non-attached device")

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
                Logger.i("submitUrbRequest", "Bulk Transfer ${buff.array().size} bytes ${if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) "in" else "out"} on EP ${targetEndpoint?.endpointNumber}")
                res = doBulkTransferInChunks(
                    context.devConn,
                    targetEndpoint!!,
                    buff.array(),
                    300
                )
                Logger.i("submitUrbRequest", "Bulk Transfer complete with $res bytes (wanted ${msg.transferBufferLength})")
            }
            USB_ENDPOINT_XFER_INT -> {
                Logger.i("submitUrbRequest","Interrupt transfer ${msg.transferBufferLength} bytes ${if (msg.direction == UsbIpBasicPacket.USBIP_DIR_IN) "in" else "out"} on EP ${targetEndpoint?.endpointNumber}")
                res = doInterruptTransfer(
                    context.devConn,
                    targetEndpoint!!,
                    buff.array(),
                    100
                )
                Logger.i("submitUrbRequest", "Interrupt transfer complete with $res bytes (wanted ${msg.transferBufferLength})")
            }
            else -> throw IOException("Unsupported endpoint type: $endpointType")
        }

        currentCoroutineContext().ensureActive()

        if (res < 0) { // If the request failed, check if the device is still around
            if (getDevice(deviceId) == null) {
                throw IOException("USB device ($deviceId) disappeared during transfer.")
            }
            reply.status = res
        } else {
            reply.actualLength = res
            reply.status = ProtocolCodes.STATUS_OK
        }

        context.activeMessagesMutex.withLock {
            if(!context.activeMessages.remove(msg.seqNum)) return
        }

        context.socketWriteMutex.withLock {
            Logger.i("submitUrbRequest", "$reply")
            s.getOutputStream().write(reply.serialize())
        }
    }

    private suspend fun abortUrbRequest(s: Socket, msg: UsbIpUnlinkUrb) {
        val context: AttachedDeviceContext = attachedDevices.get(s) ?: return
        var found = false
        context.activeMessagesMutex.withLock {
            if(context.activeMessages.remove(msg.seqNumToUnlink)){
                found = true
            }
        }
        val reply = UsbIpUnlinkUrbReply(msg.seqNum, msg.devId, msg.direction, msg.ep)
        reply.status = if (found) UsbIpBasicPacket.USBIP_ECONNRESET else 0
        context.socketWriteMutex.withLock {
            Logger.i("abortUrbRequest", "$reply")
            s.getOutputStream().write(reply.serialize())
        }
    }
}