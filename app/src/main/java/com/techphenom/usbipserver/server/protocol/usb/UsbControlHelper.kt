package com.techphenom.usbipserver.server.protocol.usb

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.SparseArray
import com.techphenom.usbipserver.server.AttachedDeviceContext
import com.techphenom.usbipserver.server.protocol.utils.Logger
import java.nio.ByteBuffer

class UsbControlHelper {
    companion object {
        private const val TAG = "UsbControlHelper"
        private const val GET_DESCRIPTOR_REQUEST_TYPE = 0x80
        private const val GET_DESCRIPTOR_REQUEST = 0x06
        private const val GET_CONFIGURATION_REQUEST = 0x08

        private const val GET_STATUS_REQUEST_TYPE = 0x82
        private const val GET_STATUS_REQUEST = 0x00

        private const val CLEAR_FEATURE_REQUEST_TYPE = 0x02
        private const val CLEAR_FEATURE_REQUEST = 0x01

        private const val SET_CONFIGURATION_REQUEST_TYPE: Int = 0x00
        private const val SET_CONFIGURATION_REQUEST: Int = 0x9

        private const val SET_INTERFACE_REQUEST_TYPE: Int = 0x01
        private const val SET_INTERFACE_REQUEST: Int = 0xB

        private const val SET_ADDRESS_REQUEST_TYPE: Int = 0x00
        private const val SET_ADDRESS_REQUEST: Int = 0x05

        private const val FEATURE_VALUE_HALT = 0x00

        private const val DEVICE_DESCRIPTOR_TYPE = 1

        fun readDeviceDescriptor(usbLib: UsbLib, context: AttachedDeviceContext): UsbDeviceDescriptor? {
            val descriptorBuffer = ByteBuffer.allocateDirect(UsbDeviceDescriptor.DESCRIPTOR_SIZE)
            val res: Int = usbLib.doControlTransfer(
                context.devConn.fileDescriptor,
                GET_DESCRIPTOR_REQUEST_TYPE.toByte(),
                GET_DESCRIPTOR_REQUEST.toByte(),
                (DEVICE_DESCRIPTOR_TYPE shl 8 or 0x00).toShort(),  // Devices only have 1 descriptor
                0, descriptorBuffer, descriptorBuffer.capacity(), 0
            )
            return if (res != UsbDeviceDescriptor.DESCRIPTOR_SIZE) {
                null
            } else UsbDeviceDescriptor(descriptorBuffer)
        }
        fun getActiveConfigurationValue(usbLib: UsbLib, context: AttachedDeviceContext): Byte {
            val buffer = ByteBuffer.allocateDirect(1)
            val bytesRead = usbLib.doControlTransfer(
                context.devConn.fileDescriptor,
                GET_DESCRIPTOR_REQUEST_TYPE.toByte(),
                GET_CONFIGURATION_REQUEST.toByte(),
                0, 0,
                buffer,
                buffer.capacity(),
                0
            )
            return if (bytesRead != 1) 0 else buffer.get()
        }
        fun doInternalControlTransfer(
            context: AttachedDeviceContext,
            requestType: Int,
            request: Int,
            value: Int,
            index: Int
        ) {
            if (requestType == SET_CONFIGURATION_REQUEST_TYPE && request == SET_CONFIGURATION_REQUEST) {
                Logger.i(TAG, "Handling SET_CONFIGURATION via Android API")
                val newConfig = (context.activeConfig?.id ?: -1) != value

                for (i in 0..<context.device.configurationCount) {
                    val config = context.device.getConfiguration(i)
                    if (config.id == value) {
                        // Reset interfaces regardless if it is a new config or not
                        if (context.activeConfig != null) {
                            Logger.i(TAG,"Unclaiming all interfaces from old config: " + context.activeConfig!!.id)
                            for (j in 0..<context.activeConfig!!.interfaceCount) {
                                context.devConn.releaseInterface(context.activeConfig!!.getInterface(j))
                            }
                        }

                        if (newConfig) {
                            if (!context.devConn.setConfiguration(config)) {
                                Logger.w(TAG, "Hardware setConfiguration($value) failed (Device might already be configured). Proceeding.")
                            }
                        } else {
                            Logger.i(TAG, "Skipping hardware setConfiguration: Device already in Config $value")
                        }

                        context.activeConfig = config // This is now the active configuration

                        Logger.i(TAG,"Claiming all interfaces from new configuration: " + context.activeConfig!!.id)
                        for (j in 0..<context.activeConfig!!.interfaceCount) {
                            val iface = context.activeConfig!!.getInterface(j)
                            if (!context.devConn.claimInterface(iface, true)) {
                                Logger.e(TAG,"Unable to claim interface: ${iface.id}")
                            }
                        }

                        buildEndpointCache(context)
                        return
                    }
                }
                Logger.e(TAG, "SET_CONFIGURATION specified invalid configuration: $value")
            } else if (requestType == SET_INTERFACE_REQUEST_TYPE && request == SET_INTERFACE_REQUEST) {
                Logger.i(TAG, "Handling SET_INTERFACE via Android API")

                if (context.activeConfig == null) {
                    Logger.e(TAG, "Attempted to use SET_INTERFACE before SET_CONFIGURATION!")
                    return
                }

                val currentInterface = context.activeConfig?.findInterface(index)
                if (currentInterface == null) {
                    Logger.e(TAG, "SET_INTERFACE failed: could not find an existing interface with ID #$index")
                    return
                }

                val targetInterface = context.activeConfig?.findInterface(index, value)
                if (targetInterface == null) {
                    Logger.e(TAG, "SET_INTERFACE specified invalid interface/alternate setting: #$index/$value")
                    return
                }

                Logger.i(TAG, "Releasing claim on current interface #${currentInterface.id}")
                context.devConn.releaseInterface(currentInterface)

                if (!context.devConn.setInterface(targetInterface)) {
                    Logger.e(TAG, "Unable to set interface: ${targetInterface.id}. Restoring old claim.")
                    context.devConn.claimInterface(currentInterface, true)
                    return
                }

                if (!context.devConn.claimInterface(targetInterface, true)) {
                    Logger.e(TAG, "Critical error: Set interface but failed to claim it: ${targetInterface.id}")
                }

                buildEndpointCache(context)
            }
        }

        fun buildEndpointCache(context: AttachedDeviceContext) {
            context.activeConfigEndpointCache = SparseArray<UsbEndpoint>()
            for (j in 0..<context.activeConfig!!.interfaceCount) {
                val iface = context.activeConfig!!.getInterface(j)
                for (k in 0..<iface.endpointCount) {
                    val ep = iface.getEndpoint(k)
                    context.activeConfigEndpointCache?.put(
                        ep.direction or ep.endpointNumber,
                        ep
                    )
                }
            }
        }

        private fun UsbConfiguration.findInterface(id: Int, alternateSetting: Int? = null): UsbInterface? {
            for (i in 0..<this.interfaceCount) {
                val iface = this.getInterface(i)
                if (iface.id == id && (alternateSetting == null || iface.alternateSetting == alternateSetting)) {
                    return iface
                }
            }
            return null
        }

        fun handleTransferInternally(requestType: Int, request: Int): Boolean {
            return (requestType == SET_CONFIGURATION_REQUEST_TYPE && request == SET_CONFIGURATION_REQUEST) ||
                    (requestType == SET_INTERFACE_REQUEST_TYPE && request == SET_INTERFACE_REQUEST)
        }
    }
}