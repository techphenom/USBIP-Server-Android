package com.techphenom.usbipserver.data

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

class UsbIpRepositoryImpl: UsbIpRepository {

    private var _usbIpServiceStatus = MutableLiveData<Boolean>()
    private var _usbDeviceList = MutableLiveData<List<UsbDeviceWithState>>()

    override fun getServiceStatus(): LiveData<Boolean> {
        return _usbIpServiceStatus
    }

    override fun setServiceStatus(status: Boolean) {
        _usbIpServiceStatus.postValue(status)
    }

    override fun getUsbDevices(): LiveData<List<UsbDeviceWithState>> {
        return _usbDeviceList
    }

    override fun setUsbDevices(devices: List<UsbDeviceWithState>) {
        _usbDeviceList.postValue(devices)
    }

    override fun updateUsbDevice(device: UsbDeviceWithState) {
        val currentList = _usbDeviceList.value ?: emptyList()
        val newList = currentList.filterNot {
            it.device.deviceId == device.device.deviceId
        }
        _usbDeviceList.postValue(newList.plus(device))
    }

    override fun removeUsbDevice(device: UsbDeviceWithState) {
        val currentList = _usbDeviceList.value ?: emptyList()
        val newList = currentList.filterNot {
            it.device.deviceId == device.device.deviceId
        }
        _usbDeviceList.postValue(newList)
    }

    override fun getLocalIpAddress(): String? {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp || networkInterface.isVirtual) {
                    continue // Skip loopback, virtual, and down interfaces
                }
                val inetAddresses = networkInterface.inetAddresses
                while (inetAddresses.hasMoreElements()) {
                    val inetAddress = inetAddresses.nextElement()
                    //  Check for IPv4 and ensure it's a site-local address (common for LAN)
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address && inetAddress.isSiteLocalAddress) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: SocketException) {
            Log.e("IPAddress", "SocketException in getLocalIpAddress: ${ex.message}")
        }
        return "N/A"
    }

}