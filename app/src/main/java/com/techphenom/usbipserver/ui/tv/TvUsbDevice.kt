package com.techphenom.usbipserver.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.techphenom.usbipserver.R
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.UsbIpDeviceState
import com.techphenom.usbipserver.server.protocol.utils.deviceIdToBusNum
import com.techphenom.usbipserver.server.protocol.utils.deviceIdToDevNum
import com.techphenom.usbipserver.ui.theme.USBOverIPTheme

@Composable
fun TvUsbDevice(
    state: UsbIpDeviceState,
    deviceName: String,
    productName: String,
    manufacturer: String,
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
    Card(
        onClick = { callback(deviceName) },
        modifier
            .width(196.dp)
            .aspectRatio(16f / 9f)
    ) {
        Box (modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = usbIcon),
                contentDescription = "USB Image",
                colorFilter = ColorFilter.tint(color),
                modifier = Modifier
                    .size(100.dp)
            )
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                            ),
                            startY = 130f
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = productName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = manufacturer,
                        style = MaterialTheme.typography.bodySmall,
                        color = (MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(end = 4.dp)
                    )
                    if(state == UsbIpDeviceState.CONNECTED || state == UsbIpDeviceState.CONNECTABLE){
                        Text(
                            text = "${deviceIdToBusNum(deviceId)}-${deviceIdToDevNum(deviceId)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = (MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = false)
@Composable
fun USBDevicePreview() {
    USBOverIPTheme(darkTheme = true) {
        TvUsbDevice(
            state = UsbIpDeviceState.CONNECTABLE,
            productName = "Solo Key Ultimate V4.1.1",
            deviceName = "/dev/002",
            manufacturer = "Solokeys the Best Manufacturer",
            deviceId = 2003,
            callback = {}
        )
    }
}