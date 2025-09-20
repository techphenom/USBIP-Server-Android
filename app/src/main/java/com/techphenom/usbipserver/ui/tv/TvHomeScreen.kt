package com.techphenom.usbipserver.ui.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.techphenom.usbipserver.ui.componets.USBDevice
import androidx.compose.runtime.livedata.observeAsState
import com.techphenom.usbipserver.ui.views.HomeScreenViewModel


@Composable
fun TvHomeScreen(
    onClick: (running: Boolean?) -> Unit,
    callback: (device: String) -> Unit,
    viewModel: HomeScreenViewModel = hiltViewModel()
    ) {

    val isServiceRunning by viewModel.isServiceRunning.observeAsState()
    val usbDevices by viewModel.usbDevices.observeAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column( // Left Column
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(5.dp)
                .weight(1f)
                .fillMaxHeight()
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isServiceRunning == true) "USB/IP Server Listening on ${viewModel.getLocalIpAddress}"
                else "USB/IP Server Not Running",
                modifier = Modifier.padding(8.dp)
            )
            Button(
                onClick = {
                    onClick(viewModel.isServiceRunning.value)
                },
                modifier = Modifier.padding(8.dp)
            ) {
                Text(if (isServiceRunning == true) "Stop Server" else "Start Server")
            }
        }
        Column( // Right Column
            modifier = Modifier
                .padding(5.dp)
                .weight(1f)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "List of Attached USB Devices",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(8.dp)
            )
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (usbDevices?.isNotEmpty() == true) {
                LazyColumn(modifier = Modifier.fillMaxHeight()) {
                    items(usbDevices!!) {
                        USBDevice(
                            state = it.state,
                            deviceName = it.device.deviceName,
                            productName = it.device.productName ?: "Unknown",
                            manufacturer = it.device.manufacturerName,
                            deviceId = it.device.deviceId,
                            callback = callback
                        )
                    }
                }
            } else {
                Text(
                    text = "No USB Devices Attached",
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
                        .fillMaxHeight()
                        .wrapContentHeight(Alignment.CenterVertically)
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                )
            }
        }
    }
}




