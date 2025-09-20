package com.techphenom.usbipserver

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.SparseArray
import androidx.core.app.NotificationCompat
import androidx.core.util.isEmpty
import com.techphenom.usbipserver.data.UsbIpRepository
import com.techphenom.usbipserver.server.AttachedDeviceContext
import com.techphenom.usbipserver.server.UsbIpServer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.core.util.size
import com.techphenom.usbipserver.data.UsbDeviceWithState
import com.techphenom.usbipserver.server.UsbIpDeviceConstants.UsbIpDeviceState
import com.techphenom.usbipserver.server.protocol.utils.Logger

@AndroidEntryPoint
class UsbIpService: Service() {
    @Inject
    lateinit var repository: UsbIpRepository

    private lateinit var usbManager: UsbManager
    private lateinit var server: UsbIpServer
    private lateinit var cpuWakeLock: PowerManager.WakeLock
    private lateinit var lowLatencyWifiLock: WifiManager.WifiLock

    enum class Actions {
        START, STOP
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.setServiceStatus(false)

        server.stop()
        lowLatencyWifiLock.release()
        cpuWakeLock.release()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            Actions.START.toString() -> start()
            Actions.STOP.toString() -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun start() {
        repository.setServiceStatus(true)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val wm = getSystemService(WIFI_SERVICE) as WifiManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        lowLatencyWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "UsbOverIp:LowLatency")
        lowLatencyWifiLock.acquire()
        cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbOverIp:WakeLock")
        cpuWakeLock.acquire()

        server = UsbIpServer(repository, usbManager, ::onEventTriggered)
        server.start()

        updateNotification()
    }

    private fun updateNotification() {
        val stopSelf = Intent(this, UsbIpService::class.java)
        stopSelf.action = Actions.STOP.toString()
        val pStopSelf: PendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopSelf,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(
            this,
            "running_channel")
            .setSmallIcon(R.drawable.usb_on)
            .setContentTitle("USB/IP Server Running")
            .setContentText( if (attachedDevices.isEmpty()) "No devices are being shared."
                                else "Sharing ${attachedDevices.size} devices.")
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(R.drawable.usb_off, "Stop Server", pStopSelf)
            .build()
        try{
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        } catch (ex: SecurityException) {
            Logger.e("updateNotif", "Couldn't Start Foreground Service: Security Exception Thrown")
        }
    }

    private fun onEventTriggered(event: UsbIpEvent) {
        return when(event) {
            is UsbIpEvent.OnUpdateNotificationEvent -> updateNotification()
            is UsbIpEvent.DeviceConnectedEvent -> {
                repository.updateUsbDevice(
                    UsbDeviceWithState(event.device, UsbIpDeviceState.CONNECTED
                    )
                )
            }
            is UsbIpEvent.DeviceDisconnectedEvent -> {
                repository.updateUsbDevice(
                    UsbDeviceWithState(event.device, UsbIpDeviceState.CONNECTABLE
                    )
                )
            }
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    companion object {
        private const val NOTIFICATION_ID: Int = 1
        val attachedDevices = SparseArray<AttachedDeviceContext>()
    }
}