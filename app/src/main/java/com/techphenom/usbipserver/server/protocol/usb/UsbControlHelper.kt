package com.techphenom.usbipserver.server.protocol.usb

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.SparseArray
import com.techphenom.usbipserver.server.AttachedDeviceContext
import com.techphenom.usbipserver.server.protocol.utils.Logger

class UsbControlHelper {
    companion object {
        private const val TAG = "UsbControlHelper"
        private const val GET_DESCRIPTOR_REQUEST_TYPE = 0x80
        private const val GET_DESCRIPTOR_REQUEST = 0x06

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
            val descriptorBuffer = ByteArray(UsbDeviceDescriptor.DESCRIPTOR_SIZE)
            val res: Int = usbLib.doControlTransfer(
                context.devConn.fileDescriptor,
                GET_DESCRIPTOR_REQUEST_TYPE.toByte(),
                GET_DESCRIPTOR_REQUEST.toByte(),
                (DEVICE_DESCRIPTOR_TYPE shl 8 or 0x00).toShort(),  // Devices only have 1 descriptor
                0, descriptorBuffer, descriptorBuffer.size, 0
            )
            return if (res != UsbDeviceDescriptor.DESCRIPTOR_SIZE) {
                null
            } else UsbDeviceDescriptor(descriptorBuffer)
        }
        fun doInternalControlTransfer(
            context: AttachedDeviceContext,
            requestType: Int,
            request: Int,
            value: Int,
            index: Int
        ): Boolean {
            if (requestType == SET_CONFIGURATION_REQUEST_TYPE && request == SET_CONFIGURATION_REQUEST) {
                Logger.i(TAG, "Handling SET_CONFIGURATION via Android API")

                for (i in 0..<context.device.configurationCount) {
                    val config = context.device.getConfiguration(i)
                    if (config.id == value) {
                        // If we have a current config, we need unclaim all interfaces to allow the
                        // configuration change to work properly.
                        if (context.activeConfig != null) {
                            Logger.i(TAG,"Unclaiming all interfaces from old config: " + context.activeConfig!!.id)
                            for (j in 0..<context.activeConfig!!.interfaceCount) {
                                val iface: UsbInterface? = context.activeConfig!!.getInterface(j)
                                context.devConn.releaseInterface(iface)
                            }
                        }

                        if (!context.devConn.setConfiguration(config)) {
                            // This can happen for certain types of devices where Android itself
                            // has set the configuration for us. Let's just hope that whatever the
                            // client wanted is also what Android selected :/
                            Logger.e(TAG, "Failed to set configuration! Proceeding anyway!")
                        }

                        context.activeConfig = config // This is now the active configuration
                        buildEndpointCache(context)

                        Logger.i(TAG,"Claiming all interfaces from new configuration: " + context.activeConfig!!.id)
                        for (j in 0..<context.activeConfig!!.interfaceCount) {
                            val iface = context.activeConfig!!.getInterface(j)
                            if (!context.devConn.claimInterface(iface, true)) {
                                Logger.e(TAG,"Unable to claim interface: ${iface.id}")
                            }
                        }

                        return true
                    }
                }

                Logger.e(TAG, "SET_CONFIGURATION specified invalid configuration: $value")
            } else if (requestType == SET_INTERFACE_REQUEST_TYPE && request == SET_INTERFACE_REQUEST) {
                Logger.i(TAG, "Handling SET_INTERFACE via Android API")

                if (context.activeConfig == null) {
                    Logger.e(TAG, "Attempted to use SET_INTERFACE before SET_CONFIGURATION!")
                    return false
                }

                val currentInterface = context.activeConfig?.findInterface(index)
                if (currentInterface == null) {
                    Logger.e(TAG, "SET_INTERFACE failed: could not find an existing interface with ID #$index")
                    return false
                }

                val targetInterface = context.activeConfig?.findInterface(index, value)
                if (targetInterface == null) {
                    Logger.e(TAG, "SET_INTERFACE specified invalid interface/alternate setting: #$index/$value")
                    return false
                }

                Logger.i(TAG, "Releasing claim on current interface #${currentInterface.id}")
                context.devConn.releaseInterface(currentInterface)

                if (!context.devConn.setInterface(targetInterface)) {
                    Logger.e(TAG, "Unable to set interface: ${targetInterface.id}. Restoring old claim.")
                    context.devConn.claimInterface(currentInterface, true)
                    return false
                }

                if (!context.devConn.claimInterface(targetInterface, true)) {
                    Logger.e(TAG, "Critical error: Set interface but failed to claim it: ${targetInterface.id}")
                }

                buildEndpointCache(context)

                return true
            }

            return false
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

        private fun android.hardware.usb.UsbConfiguration.findInterface(id: Int, alternateSetting: Int? = null): UsbInterface? {
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