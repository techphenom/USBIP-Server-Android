package com.techphenom.usbipserver.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.techphenom.usbipserver.BuildConfig


@Composable
fun HomeScreen(
    onClick: (running: Boolean?) -> Unit,
    callback: (device: String) -> Unit,
    viewModel: HomeScreenViewModel = hiltViewModel()
    ) {

    val isServiceRunning by viewModel.isServiceRunning.observeAsState()
    val usbDevices by viewModel.usbDevices.observeAsState()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if(BuildConfig.DEBUG) {
                    Surface(
                        color = Color.Yellow.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "DEBUG BUILD",
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            color = Color.Black,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if(isServiceRunning == true) "USB/IP Server Listening on ${viewModel.getLocalIpAddress}"
                    else "USB/IP Server Stopped",
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
                HorizontalDivider(thickness = 2.dp, modifier = Modifier.fillMaxWidth(fraction = 0.9f))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            if(usbDevices?.isNotEmpty() == true) {
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
            }
            else {
                Text(
                    text = "No USB Devices Attached",
                    textAlign = TextAlign.Center,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .wrapContentHeight(Alignment.CenterVertically)
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
        ) {
            Button(
                onClick = {
                    onClick(viewModel.isServiceRunning.value)
                },
                modifier = Modifier
                    .fillMaxWidth(fraction = 0.75f)
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                Text(if (isServiceRunning == true) "Stop Server" else "Start Server")
            }
        }
    }
}


