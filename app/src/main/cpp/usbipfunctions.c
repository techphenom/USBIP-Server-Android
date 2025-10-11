#include <stdlib.h>
#include <unistd.h>
#include <jni.h>

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <android/log.h>
#include "libusb_src/libusb/libusb.h"

#define APPNAME "UsbIpServerNativeLibusb"

static libusb_context *g_ctx = NULL;

static int libusb_to_errno(int libusb_err) {
    switch (libusb_err) {
        case LIBUSB_SUCCESS: return 0;
        case LIBUSB_ERROR_IO: return -EIO;
        case LIBUSB_ERROR_INVALID_PARAM: return -EINVAL;
        case LIBUSB_ERROR_ACCESS: return -EACCES;
        case LIBUSB_ERROR_NO_DEVICE: return -ENODEV;
        case LIBUSB_ERROR_NOT_FOUND: return -ENOENT;
        case LIBUSB_ERROR_BUSY: return -EBUSY;
        case LIBUSB_ERROR_TIMEOUT: return -ETIMEDOUT; // Important
        case LIBUSB_ERROR_OVERFLOW: return -EOVERFLOW;
        case LIBUSB_ERROR_PIPE: return -EPIPE; // STALL condition
        case LIBUSB_ERROR_INTERRUPTED: return -EINTR;
        case LIBUSB_ERROR_NO_MEM: return -ENOMEM;
        case LIBUSB_ERROR_NOT_SUPPORTED: return -EOPNOTSUPP;
        case LIBUSB_ERROR_OTHER: return -EIO;
        default:
            if (libusb_err > 0) return -libusb_err;
            return libusb_err;
    }
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_init(JNIEnv *env, jobject thiz) {
    if (g_ctx != NULL) {
        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Libusb context already initialized.");
        return 0;
    }

    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int r = libusb_init_context(&g_ctx, NULL, 0);
    if (r < 0) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "libusb_init failed: %s", libusb_error_name(r));
        g_ctx = NULL;
        return libusb_to_errno(r);
    }

    __android_log_print(ANDROID_LOG_INFO, APPNAME, "Libusb context initialized successfully.");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_exit(JNIEnv *env, jobject thiz) {
    if (g_ctx != NULL) {
        libusb_exit(g_ctx);
        g_ctx = NULL;
        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Libusb context de-initialized.");
    }
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_doControlTransfer(JNIEnv *env,
                                                                           jobject thiz,
                                                                           jint fd,
                                                                           jbyte request_type,
                                                                           jbyte request,
                                                                           jshort value,
                                                                           jshort index,
                                                                           jbyteArray data,
                                                                           jint length,
                                                                           jint timeout) {
    libusb_device_handle *dev_handle = NULL;
    int r;
    jint result_status = -EIO;
    unsigned char *native_buffer = NULL;

    if (g_ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Ctrl: Libusb context not initialized! Call init() first.");
        return -EFAULT;
    }

    r = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &dev_handle);
    if (r < 0 || dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Ctrl: libusb_wrap_sys_device for fd %d failed: %s", fd, libusb_error_name(r));
        return libusb_to_errno(r);
    }

    if (length > 0 && data != NULL) {
        native_buffer = (unsigned char *) (*env)->GetPrimitiveArrayCritical(env, data, NULL);
        if (native_buffer == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Ctrl: GetPrimitiveArrayCritical failed for control transfer");
            result_status = -ENOMEM;
            goto cleanup;
        }
    }

    r = libusb_control_transfer(dev_handle,
                                request_type,
                                request,
                                value,
                                index,
                                native_buffer,
                                (uint16_t)length,
                                (unsigned int)timeout);

    if (r < 0) {
        if (r == LIBUSB_ERROR_TIMEOUT) {
            __android_log_print(ANDROID_LOG_WARN, APPNAME, "Ctrl: libusb_control_transfer timed out");
            result_status = -ETIMEDOUT;
        } else if (r == LIBUSB_ERROR_PIPE) {
            __android_log_print(ANDROID_LOG_WARN, APPNAME, "Ctrl: libusb_control_transfer STALL (pipe error)");
            result_status = -EPIPE;
        } else {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Ctrl: libusb_control_transfer failed: %s (code %d)", libusb_error_name(r), r);
            result_status = libusb_to_errno(r);
        }
    } else {
        result_status = r;
    }

    if (native_buffer != NULL) {
        int release_mode = JNI_ABORT;
        if ((request_type & LIBUSB_ENDPOINT_IN) && r >= 0) {
            release_mode = 0;
        }
        (*env)->ReleasePrimitiveArrayCritical(env, data, native_buffer, release_mode);
        native_buffer = NULL;
    }

    cleanup:
    if (dev_handle != NULL) {
        libusb_close(dev_handle);
    }

    return result_status;
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_doBulkTransfer(JNIEnv *env,
                                                                        jobject thiz,
                                                                        jint fd,
                                                                        jint endpoint,
                                                                        jbyteArray data,
                                                                        jint timeout) {
    libusb_device_handle *dev_handle = NULL;
    int r;
    jint result_status = -EIO;
    unsigned char *native_buffer = NULL;
    int actual_length_transferred = 0;

    if (g_ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Bulk: Libusb context not initialized! Call init() first.");
        return -EFAULT;
    }

    r = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &dev_handle);
    if (r < 0 || dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Bulk: libusb_wrap_sys_device for fd %d failed: %s", fd, libusb_error_name(r));
        return libusb_to_errno(r);
    }

    jsize dataLen = data ? (*env)->GetArrayLength(env, data) : 0;

    if (dataLen > 0) {
        native_buffer = (unsigned char *) (*env)->GetPrimitiveArrayCritical(env, data, NULL);
        if (native_buffer == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Bulk: GetPrimitiveArrayCritical failed for ep 0x%02X", endpoint);
            result_status = -ENOMEM;
            goto cleanup;
        }
    }

    r = libusb_bulk_transfer(dev_handle,
                             (unsigned char) endpoint,
                             native_buffer,
                             dataLen,
                             &actual_length_transferred,
                             (unsigned int) timeout);

    if (r == LIBUSB_SUCCESS) {
        result_status = actual_length_transferred;
    } else {
        if (r == LIBUSB_ERROR_TIMEOUT) {
            __android_log_print(ANDROID_LOG_WARN, APPNAME, "Bulk: libusb_bulk_transfer timed out for ep 0x%02X", endpoint);
            result_status = -ETIMEDOUT;
        } else if (r == LIBUSB_ERROR_PIPE) {
            __android_log_print(ANDROID_LOG_WARN, APPNAME, "Bulk: libusb_bulk_transfer STALL (pipe error) for ep 0x%02X", endpoint);
            result_status = -EPIPE;
        } else {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Bulk: libusb_bulk_transfer failed for ep 0x%02X: %s (code %d)", endpoint, libusb_error_name(r), r);
            result_status = libusb_to_errno(r);
        }
    }

    if (native_buffer != NULL) {
        int release_mode = JNI_ABORT;
        if ((endpoint & LIBUSB_ENDPOINT_IN) && r == LIBUSB_SUCCESS) {
            release_mode = 0;
        }
        (*env)->ReleasePrimitiveArrayCritical(env, data, native_buffer, release_mode);
        native_buffer = NULL;
    }

    cleanup:
    if (dev_handle != NULL) {
        libusb_close(dev_handle);
    }

    return result_status;
}


JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_doInterruptTransfer(JNIEnv *env,
                                                                               jobject thiz,
                                                                               jint fd,
                                                                               jint endpoint,
                                                                               jbyteArray data,
                                                                               jint timeout) {
    libusb_device_handle *dev_handle = NULL;
    int r;
    jint result_status = -EIO;
    unsigned char *native_buffer = NULL;
    int actual_length_transferred = 0;

    if (g_ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Intr: Libusb context not initialized! Call init() first.");
        return -EFAULT;
    }

    r = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &dev_handle);
    if (r < 0 || dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Intr: libusb_wrap_sys_device for fd %d failed: %s", fd, libusb_error_name(r));
        return libusb_to_errno(r);
    }

    jsize dataLen = data ? (*env)->GetArrayLength(env, data) : 0;
    if (dataLen > 0) {
        native_buffer = (unsigned char *) (*env)->GetPrimitiveArrayCritical(env, data, NULL);
        if (native_buffer == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Intr: GetPrimitiveArrayCritical failed for ep 0x%02X", endpoint);
            result_status = -ENOMEM;
            goto cleanup;
        }
    }

    r = libusb_interrupt_transfer(dev_handle,
                                  (unsigned char) endpoint,
                                  native_buffer,
                                  (int) dataLen,
                                  &actual_length_transferred,
                                  (unsigned int) timeout);

    if (r == LIBUSB_SUCCESS) {
        result_status = actual_length_transferred;
    } else {
        if (r == LIBUSB_ERROR_TIMEOUT) {
            __android_log_print(ANDROID_LOG_WARN, APPNAME, "Intr: libusb_interrupt_transfer timed out for ep 0x%02X", endpoint);
            result_status = -ETIMEDOUT;
        } else if (r == LIBUSB_ERROR_PIPE) {
            __android_log_print(ANDROID_LOG_WARN, APPNAME, "Intr: libusb_interrupt_transfer STALL (pipe error) for ep 0x%02X", endpoint);
            result_status = -EPIPE;
        } else {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Intr: libusb_interrupt_transfer failed for ep 0x%02X: %s (code %d)", endpoint, libusb_error_name(r), r);
            result_status = libusb_to_errno(r);
        }
    }

    if (native_buffer != NULL) {
        int release_mode = JNI_ABORT;
        if ((endpoint & LIBUSB_ENDPOINT_IN) && r == LIBUSB_SUCCESS) {
            release_mode = 0;
        }
        (*env)->ReleasePrimitiveArrayCritical(env, data, native_buffer, release_mode);
        native_buffer = NULL;
    }

    cleanup:
    if (dev_handle != NULL) {
        libusb_close(dev_handle);
    }

    return result_status;
}