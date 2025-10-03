package com.techphenom.usbipserver.ui.tv

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.*
import com.techphenom.usbipserver.ui.componets.AboutAppDialog
import com.techphenom.usbipserver.ui.views.HomeScreenViewModel


@Composable
fun TvHomeScreen(
    onClick: (running: Boolean?) -> Unit,
    callback: (device: String) -> Unit,
    viewModel: HomeScreenViewModel = hiltViewModel()
    ) {

    val isServiceRunning by viewModel.isServiceRunning.observeAsState()
    val usbDevices by viewModel.usbDevices.observeAsState()

    var menuExpanded by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }


    Column (
        modifier = Modifier
            .padding(58.dp, 28.dp)
            .fillMaxHeight()
            .fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    onClick(viewModel.isServiceRunning.value)
                }
            ) {
                Text(if (isServiceRunning == true) "Stop Server" else "Start Server")
            }
            Spacer(modifier = Modifier.width(24.dp))
            Text(
                text = if (isServiceRunning == true) "USB/IP Server Listening on ${viewModel.getLocalIpAddress}"
                else "USB/IP Server Not Running",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("About") },
                        onClick = {
                            menuExpanded = false
                            showAboutDialog = true
                        }
                    )
                }
            }
        }

        if (usbDevices?.isNotEmpty() == true) {
            TvUsbDeviceList(
                "Connected",
                usbDevices!!.filter { it.state == UsbIpDeviceState.CONNECTED },
                callback
            )
            TvUsbDeviceList(
                "Connectable",
                usbDevices!!.filter { it.state == UsbIpDeviceState.CONNECTABLE },
                callback
            )
            TvUsbDeviceList(
                "Not Connectable",
                usbDevices!!.filter { it.state == UsbIpDeviceState.NOT_CONNECTABLE },
                callback
            )
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
        if (showAboutDialog) {
            AboutAppDialog(onDismissRequest = { showAboutDialog = false })
        }
    }
}




