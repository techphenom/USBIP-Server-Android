# USBIP Server - Android (Alpha)

**Share your Android device's USB peripherals over the network using the USB/IP protocol.**

This application allows your Android device (including Android TV) to act as a USB/IP server, enabling you to share connected USB devices (like flash drives, a dolphinbar, etc.) with other computers on your network as if they were directly connected to the client machine.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

## Important Notes

*   This application is a work-in-progress that builds upon the work done by cgutman and his [USBIPServerForAndroid](#https://github.com/cgutman/USBIPServerForAndroid) project.
*   For now, USB webcams and mics will NOT work. I'm not handling isochronous transfers.
*   Works only with clients running USBIP version 1.1.1
*   For a complete list of devices I've tested, please checkout the [wiki](https://github.com/techphenom/usbip-server-android/wiki/Devices-Tested).

## Features

*   **USB/IP Server Implementation:** Core functionality to share USB devices.
*   **Android TV Interface:** User-friendly UI designed for TV navigation, allowing you to manage the server and see connected devices from your TV.
*   **Device Listing:** Clearly lists available USB devices connected to the Android host.
*   **Status Indication:** Shows whether the server is running and which devices are currently being shared.
*   **(Potentially) Client IP Indication:** Displays the local IP address the server is listening on for easy client configuration.


## How It Works

The application leverages the USB/IP protocol. The Android device runs the USB/IP server component, "exporting" its connected USB devices. A client machine on the same network (running a USB/IP client like the one available in the Linux kernel) can then "import" these devices, making them appear as local USB devices on the client.

## Getting Started

### Prerequisites

*   An Android device (phone, tablet, or Android TV) to act as the server.
*   Android Version: 11+
*   (For TV) NVIDIA Shield or other Android TV device.
*   A client machine (with appropriate USB/IP client software) on the same network.

### Installation

1.  **Download the APK:**
    *   [Latest Release](https://github.com/techphenom/usbip-server-android/releases)
    *   Or, build the APK from the source code.
2.  **Install the APK on your Android device**
