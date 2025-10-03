package com.techphenom.usbipserver.ui.componets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.techphenom.usbipserver.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.techphenom.usbipserver.BuildConfig

@Composable
fun AboutAppDialog(
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text("About USB/IP Server")
        },
        text = {
            Row {
                Image(
                    painter = painterResource(id = R.drawable.usbip_server_logo),
                    contentDescription = "USB/IP Server Logo"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                ) {
                    Text("Version: ${BuildConfig.VERSION_NAME}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Build Number: ${BuildConfig.VERSION_CODE}")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("OK")
            }
        }
    )
}


@Preview
@Composable
fun AboutAppDialogPreview() {
    AboutAppDialog(onDismissRequest = {})
}