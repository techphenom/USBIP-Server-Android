package com.techphenom.usbipserver.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.techphenom.usbipserver.data.UsbDeviceWithState
import com.techphenom.usbipserver.server.UsbIpDeviceConstants

@Composable
fun TvUsbDeviceList(
    title: String,
    devices: List<UsbDeviceWithState> = emptyList(),
    callback: (device: String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column {
            Text(
                title + " (" + devices.size + ")",
                modifier = Modifier.padding(bottom = 4.dp)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) {
                    TvUsbDevice(
                        state = it.state,
                        deviceName = it.device.deviceName,
                        productName = it.device.productName ?: "Unknown",
                        manufacturer = it.device.manufacturerName ?: "Unknown Manufacturer",
                        deviceId = it.device.deviceId,
                        callback = callback
                    )
                }
            }
        }
    }
}

//@Preview(showBackground = true)
//@Composable
//fun TvUsbDeviceListPreview() {
//    TvUsbDeviceList(
//        title = "Connected USB Devices",
//        devices = generateFakeDevices(),
//        callback = {}
//    )
//}

data class FakeUsbDeviceWithState(
    val device: FakeUsbDevice,
    val state: UsbIpDeviceConstants.UsbIpDeviceState
)
data class FakeUsbDevice(
    val deviceName: String,
    val deviceId: Int,
    val manufacturerName: String?,
    val productName: String?
)

fun generateFakeDevices(): List<FakeUsbDeviceWithState> {
    return listOf(
        FakeUsbDeviceWithState(
            device = FakeUsbDevice(deviceName = "/dev/bus/usb/001/002", deviceId = 2003, manufacturerName = "Logitech", productName = "Wireless Mouse M325"),
            state = UsbIpDeviceConstants.UsbIpDeviceState.CONNECTED
        ),
        FakeUsbDeviceWithState(
            device = FakeUsbDevice(deviceName = "/dev/bus/usb/001/003", deviceId = 2004, manufacturerName = "Solo Keys", productName = "Solo 4.1.1"),
            state = UsbIpDeviceConstants.UsbIpDeviceState.CONNECTED
        ),
        FakeUsbDeviceWithState(
            device = FakeUsbDevice(deviceName = "/dev/bus/usb/001/004", deviceId = 2005, manufacturerName = "Xbox", productName = "Wireless Xbox PC Adapter"),
            state = UsbIpDeviceConstants.UsbIpDeviceState.CONNECTED
        )
    )
}