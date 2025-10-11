# Sets the minimum version of CMake required to build your native library.
# This ensures that a certain set of CMake features is available to
# your build.

cmake_minimum_required(VERSION 3.18.1)
project(UsbIpServerNative C)

# Specifies a library name, specifies whether the library is STATIC or
# SHARED, and provides relative paths to the source code. You can
# define multiple libraries by adding multiple add_library() commands,
# and CMake builds them for you. When you build your app, Gradle
# automatically packages shared libraries with your APK.

# --- libusb ---
set(LIBUSB_PROJECT_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libusb_src)
set(LIBUSB_CORE_DIR ${LIBUSB_PROJECT_SOURCE_DIR}/libusb)
set(LIBUSB_ANDROID_CONFIG_DIR ${LIBUSB_PROJECT_SOURCE_DIR}/jni_config)

include_directories(
        ${LIBUSB_CORE_DIR}
        ${LIBUSB_CORE_DIR}/os
        ${LIBUSB_ANDROID_CONFIG_DIR}
)

set(LIBUSB_SOURCES
        ${LIBUSB_CORE_DIR}/core.c
        ${LIBUSB_CORE_DIR}/descriptor.c
        ${LIBUSB_CORE_DIR}/io.c
        ${LIBUSB_CORE_DIR}/strerror.c
        ${LIBUSB_CORE_DIR}/sync.c
        ${LIBUSB_CORE_DIR}/hotplug.c
        ${LIBUSB_CORE_DIR}/os/linux_usbfs.c
        ${LIBUSB_CORE_DIR}/os/events_posix.c
        ${LIBUSB_CORE_DIR}/os/threads_posix.c
        ${LIBUSB_CORE_DIR}/os/linux_netlink.c
)

add_library(libusb_static STATIC
        ${LIBUSB_SOURCES}
)

target_compile_options(libusb_static PRIVATE
        -fvisibility=hidden
        -pthread
)

target_compile_definitions(libusb_static PRIVATE
        HAVE_CONFIG_H
        _GNU_SOURCE=1
        OS_LINUX
        PLATFORM_ANDROID=1
        USBI_TIMERFD_AVAILABLE=1
        HAVE_TIMERFD=1
        ENABLE_HOTPLUG=1
)

add_library(
        usbipfunctions
        SHARED
        usbipfunctions.c
)

target_link_libraries( # Specifies the target library.
        usbipfunctions
        PRIVATE
        libusb_static
        PUBLIC
        android
        log)