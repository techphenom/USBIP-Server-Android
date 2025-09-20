package com.techphenom.usbipserver.ui.tv

import android.Manifest
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import com.techphenom.usbipserver.UsbIpService
import com.techphenom.usbipserver.data.UsbDeviceWithState
import com.techphenom.usbipserver.data.UsbIpRepository
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.UsbIpDeviceState
import com.techphenom.usbipserver.ui.theme.TvTypography
import com.techphenom.usbipserver.ui.theme.USBOverIPTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TvActivity : ComponentActivity() {
    @Inject
    lateinit var repository: UsbIpRepository
    private lateinit var usbManager: UsbManager
    private lateinit var permissionIntent: PendingIntent

    private val ACTION_USB_PERMISSION = "com.techphenom.usbipserver.USB_PERMISSION"

    private val usbPermissionReceiver: BroadcastReceiver =  object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice?
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            repository.updateUsbDevice(
                                UsbDeviceWithState(it,UsbIpDeviceState.CONNECTABLE
                                )
                            )
                        }
                    } else {
                        device?.let {
                            repository.updateUsbDevice(
                                UsbDeviceWithState(it, UsbIpDeviceState.NOT_CONNECTABLE
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private val usbUpdatedReceiver = (object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                synchronized(this) {
                    val device: UsbDevice?
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val hasPermission = usbManager.hasPermission(device)
                    if(device != null ) {
                        repository.updateUsbDevice(UsbDeviceWithState(device, UsbIpDeviceState.NOT_CONNECTABLE))
                        if(!hasPermission) {
                            usbManager.requestPermission(device, permissionIntent)
                        }
                    }
                }
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                synchronized(this) {
                    val device: UsbDevice?
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if(device != null) {
                        repository.removeUsbDevice(
                            UsbDeviceWithState(
                                device,
                                UsbIpDeviceState.NOT_CONNECTABLE
                            )
                        )
                    }
                }
            }
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).also { it.`package` = this.packageName },
            FLAG_MUTABLE
        )

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU){
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                0
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                usbPermissionReceiver,
                IntentFilter(ACTION_USB_PERMISSION),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                usbPermissionReceiver,
                IntentFilter(ACTION_USB_PERMISSION)
            )
        }

        val usbFilter = IntentFilter().also {
            it.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            it.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                usbUpdatedReceiver,
                usbFilter,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                usbUpdatedReceiver,
                usbFilter
            )
        }

        val devices = usbManager.deviceList.values.toList().map {
            val state = if(usbManager.hasPermission(it)) UsbIpDeviceState.CONNECTABLE
                            else UsbIpDeviceState.NOT_CONNECTABLE
            UsbDeviceWithState(it, state)
        }
        repository.setUsbDevices(devices)

        setContent {
            USBOverIPTheme(darkTheme = true, typography = TvTypography) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TvHomeScreen( { running ->
                        Intent(applicationContext, UsbIpService::class.java).also {
                            it.action = if(running == true) UsbIpService.Actions.STOP.toString()
                                            else UsbIpService.Actions.START.toString()
                            startForegroundService(it)
                    }}, ::requestUsbPermission)
                }
            }

        }
    }

    private fun requestUsbPermission(deviceName: String) {
        usbManager.deviceList.values.forEach {
            if(it.deviceName == deviceName) {
                if(!usbManager.hasPermission(it)) {
                    usbManager.requestPermission(it, permissionIntent)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbUpdatedReceiver)
    }
}