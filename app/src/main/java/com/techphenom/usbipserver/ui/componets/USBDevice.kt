package com.techphenom.usbipserver.ui.componets

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.techphenom.usbipserver.R
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.UsbIpDeviceState
import com.techphenom.usbipserver.server.protocol.utils.deviceIdToBusNum
import com.techphenom.usbipserver.server.protocol.utils.deviceIdToDevNum
import com.techphenom.usbipserver.ui.theme.USBOverIPTheme

@Composable
fun USBDevice(
    state: UsbIpDeviceState,
    deviceName: String,
    productName: String,
    manufacturer: String?,
    deviceId: Int,
    callback: (device: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val usbIcon = if (state == UsbIpDeviceState.NOT_CONNECTABLE) R.drawable.usb_off
                    else R.drawable.usb_on
    val color = when (state) {
        UsbIpDeviceState.CONNECTED -> Color.Green
        UsbIpDeviceState.NOT_CONNECTABLE -> Color.Gray
        UsbIpDeviceState.CONNECTABLE -> Color.Yellow
    }
    Column (modifier = modifier.padding(top = 5.dp, bottom = 5.dp)) {
        Row (
            modifier = Modifier
                .fillMaxWidth()
                //.height(150.dp)
                //.background(Color.LightGray)
                .padding(bottom = 8.dp, top = 8.dp)
        ) {
            Image(
                painter = painterResource(id = usbIcon),
                contentDescription = "USB Image",
                colorFilter = ColorFilter.tint(color),
                modifier = Modifier
                    .size(100.dp)
                    .clickable { callback(deviceName) }
            )
            Column(
                //modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = "Name: $productName")
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Manufacturer: $manufacturer")
                Spacer(modifier = Modifier.height(8.dp))
                Text("State: ${state.description}")
                if(state == UsbIpDeviceState.CONNECTED || state == UsbIpDeviceState.CONNECTABLE){
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Bus-Id: ${deviceIdToBusNum(deviceId)}-${deviceIdToDevNum(deviceId)}")
                }
            }
        }
    }
}

@Preview(showBackground = false)
@Composable
fun USBDevicePreview() {
    USBOverIPTheme {
        USBDevice(
            state = UsbIpDeviceState.CONNECTABLE,
            productName = "Solo 4.1.1",
            deviceName = "/dev/002",
            manufacturer = "Solokeys",
            deviceId = 2003,
            callback = {}
        )
    }
}