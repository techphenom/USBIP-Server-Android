package com.techphenom.usbipserver.ui.views

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.techphenom.usbipserver.data.UsbDeviceWithState
import com.techphenom.usbipserver.data.UsbIpRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeScreenViewModel @Inject constructor(
    private val repository: UsbIpRepository
): ViewModel() {

    val isServiceRunning: LiveData<Boolean> = repository.getServiceStatus()

    val usbDevices: LiveData<List<UsbDeviceWithState>> = repository.getUsbDevices()

    val getLocalIpAddress: String? = repository.getLocalIpAddress()
}