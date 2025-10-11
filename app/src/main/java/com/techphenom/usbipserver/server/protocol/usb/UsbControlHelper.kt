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
        fun handleInternalControlTransfer(
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
                        if (context.activeConfiguration != null) {
                            Logger.i(TAG, "Unclaiming all interfaces from old configuration: " + context.activeConfiguration!!.id)
                            for (j in 0..<context.activeConfiguration!!.interfaceCount) {
                                val iface: UsbInterface? = context.activeConfiguration!!.getInterface(j)
                                context.devConn.releaseInterface(iface)
                            }
                        }

                        if (!context.devConn.setConfiguration(config)) {
                            // This can happen for certain types of devices where Android itself
                            // has set the configuration for us. Let's just hope that whatever the
                            // client wanted is also what Android selected :/
                            Logger.e(TAG, "Failed to set configuration! Proceeding anyway!")
                        }

                        // This is now the active configuration
                        context.activeConfiguration = config

                        // Construct the cache of endpoint mappings
                        context.activeConfigurationEndpointsByNumDir = SparseArray<UsbEndpoint>()
                        for (j in 0..<context.activeConfiguration!!.interfaceCount) {
                            val iface = context.activeConfiguration!!.getInterface(j)
                            for (k in 0..<iface.endpointCount) {
                                val endp = iface.getEndpoint(k)
                                context.activeConfigurationEndpointsByNumDir?.put(
                                    endp.direction or endp.endpointNumber,
                                    endp
                                )
                            }
                        }

                        Logger.i(TAG,"Claiming all interfaces from new configuration: " + context.activeConfiguration!!.id)
                        for (j in 0..<context.activeConfiguration!!.interfaceCount) {
                            val iface = context.activeConfiguration!!.getInterface(j)
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

                if (context.activeConfiguration != null) {
                    for (i in 0..<context.activeConfiguration!!.interfaceCount) {
                        val iface = context.activeConfiguration!!.getInterface(i)
                        if (iface.id == index && iface.alternateSetting == value) {
                            if (!context.devConn.setInterface(iface)) {
                                Logger.e(TAG, "Unable to set interface: ${iface.id}")
                            }
                            return true
                        }
                    }
                    Logger.e(TAG, "SET_INTERFACE specified invalid interface: $index, $value")
                } else {
                    Logger.e(TAG, "Attempted to use SET_INTERFACE before SET_CONFIGURATION!")
                }
            }

            return false
        }
    }
}