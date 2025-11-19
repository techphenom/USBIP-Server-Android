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
        case LIBUSB_ERROR_TIMEOUT: return -ETIMEDOUT;
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

static void log_cb(libusb_context *ctx, enum libusb_log_level level, const char *str) {
    int priority = ANDROID_LOG_DEFAULT;
    if (level == LIBUSB_LOG_LEVEL_ERROR) {
        priority = ANDROID_LOG_ERROR;
    } else if (level == LIBUSB_LOG_LEVEL_WARNING) {
        priority = ANDROID_LOG_WARN;
    } else if (level == LIBUSB_LOG_LEVEL_INFO) {
        priority = ANDROID_LOG_INFO;
    } else {
        priority = ANDROID_LOG_DEBUG;
    }
    __android_log_print(priority, APPNAME, "log_cb: %s", str);
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_init(JNIEnv *env, jobject thiz, jboolean debug) {
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

    if (debug) {
        libusb_set_option(g_ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_DEBUG);
        libusb_set_log_cb(g_ctx, log_cb, LIBUSB_LOG_CB_GLOBAL);
        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Libusb debug mode. Logging enabled.");
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
        native_buffer = (unsigned char *) (*env)->GetByteArrayElements(env, data, NULL);
        if (native_buffer == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Ctrl: GetByteArrayElements failed for control transfer");
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
        (*env)->ReleaseByteArrayElements(env, data, (jbyte*)native_buffer, release_mode);
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

    if (g_ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Bulk: Libusb context not initialized! Call init() first.");
        return -EFAULT;
    }

    r = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &dev_handle);
    if (r < 0 || dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Bulk: libusb_wrap_sys_device for fd %d failed: %s", fd, libusb_error_name(r));
        return libusb_to_errno(r);
    }

    jsize total_length = (*env)->GetArrayLength(env, data);
    jbyte *native_buffer = (*env)->GetByteArrayElements(env, data, NULL);
    if (native_buffer == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "BulkChunks: GetByteArrayElements failed");
        libusb_close(dev_handle);
        return -ENOMEM;
    }

    const int CHUNK_SIZE = 16 * 1024; // 16 KB
    int total_bytes_transferred = 0;
    int offset = 0;

    while (offset < total_length) { // Do Bulk Transfers in chunks of 16KB
        int remaining = total_length - offset;
        int current_chunk_size = (remaining < CHUNK_SIZE) ? remaining : CHUNK_SIZE;
        int actual_length_transferred = 0;

        unsigned char *current_chunk_ptr = (unsigned char *)native_buffer + offset;

        r = libusb_bulk_transfer(dev_handle,
                                 (unsigned char) endpoint,
                                 current_chunk_ptr,
                                 current_chunk_size,
                                 &actual_length_transferred,
                                 (unsigned int) timeout);

        if (r < 0) {
            result_status = libusb_to_errno(r);
            if (r == LIBUSB_ERROR_TIMEOUT) {
                __android_log_print(ANDROID_LOG_WARN, APPNAME, "BulkChunks: Transfer timed out at offset %d", offset);
            } else {
                __android_log_print(ANDROID_LOG_ERROR, APPNAME, "BulkChunks: Transfer failed at offset %d with error: %s", offset, libusb_error_name(r));
            }
            if(total_bytes_transferred > 0) { // Just send what we have so far
                result_status = total_bytes_transferred;
            }
            goto cleanup;
        }

        total_bytes_transferred += actual_length_transferred;
        offset += actual_length_transferred;

        if (actual_length_transferred < current_chunk_size) {
            break; // Short transfer, assuming end of data
        }
    }

    result_status = total_bytes_transferred;

cleanup:
    ;
    int release_mode = (endpoint & LIBUSB_ENDPOINT_IN) ? 0 : JNI_ABORT;
    (*env)->ReleaseByteArrayElements(env, data, (jbyte*)native_buffer, release_mode);
    libusb_close(dev_handle);

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
        native_buffer = (unsigned char *) (*env)->GetByteArrayElements(env, data, NULL);
        if (native_buffer == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Intr: GetByteArrayElements failed for ep 0x%02X", endpoint);
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
        (*env)->ReleaseByteArrayElements(env, data, (jbyte*)native_buffer, release_mode);
        native_buffer = NULL;
    }

    cleanup:
    if (dev_handle != NULL) {
        libusb_close(dev_handle);
    }

    return result_status;
}

void iso_transfer_cb(struct libusb_transfer *transfer) {
    int *completed = transfer->user_data;
    *completed = 1;
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_doIsochronousTransfer(JNIEnv *env,
                                                                                 jobject thiz,
                                                                                 jint fd,
                                                                                 jint endpoint,
                                                                                 jbyteArray data,
                                                                                 jintArray iso_packet_lengths) {
    libusb_device_handle *dev_handle = NULL;
    struct libusb_transfer *transfer = NULL;
    int r;
    jint result_status = -EIO;
    unsigned char *native_buffer = NULL;
    jint *native_packet_lengths = NULL;
    int data_release_mode = JNI_ABORT;
    int packet_release_mode = JNI_ABORT;
    volatile int completed = 0;

    if (g_ctx == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Iso: Libusb context not initialized! Call init() first.");
        return -EFAULT;
    }

    r = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &dev_handle);
    if (r < 0 || dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Iso: libusb_wrap_sys_device for fd %d failed: %s", fd, libusb_error_name(r));
        result_status = libusb_to_errno(r);
        goto cleanup_handle;
    }

    jsize num_packets = (*env)->GetArrayLength(env, iso_packet_lengths);
    jsize total_length = (*env)->GetArrayLength(env, data);

    transfer = libusb_alloc_transfer(num_packets);
    if (!transfer) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Iso: libusb_alloc_transfer failed");
        result_status = -ENOMEM;
        goto cleanup_handle;
    }

    native_buffer = (unsigned char *)(*env)->GetByteArrayElements(env, data, NULL);
    if (native_buffer == NULL) {
        result_status = -ENOMEM;
        goto cleanup_transfer;
    }

    native_packet_lengths = (*env)->GetIntArrayElements(env, iso_packet_lengths, NULL);
    if (native_packet_lengths == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Iso: GetIntArrayElements for packets failed");
        result_status = -ENOMEM;
        goto cleanup_arrays;
    }

    libusb_fill_iso_transfer(transfer, dev_handle, (unsigned char)endpoint, native_buffer, total_length,
                             num_packets, iso_transfer_cb, (void *)&completed, 1000);

    for (int i = 0; i < num_packets; i++) {
        transfer->iso_packet_desc[i].length = native_packet_lengths[i];
    }

    r = libusb_submit_transfer(transfer);
    if (r < 0) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Iso: libusb_submit_transfer failed: %s", libusb_error_name(r));
        result_status = libusb_to_errno(r);
        goto cleanup_arrays;
    }

    while (!completed) {
        struct timeval tv = {5, 0};
        r = libusb_handle_events_timeout_completed(g_ctx, &tv, (int*)&completed);
        if (r < 0) {
            if (r == LIBUSB_ERROR_INTERRUPTED) {
                continue;
            }
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Iso: libusb_handle_events failed with: %s", libusb_error_name(r));
            // The transfer is now in an unknown state. Cancel it to be safe.
            libusb_cancel_transfer(transfer);
            break;
        }
    }

    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        int total_actual_length = 0;
        // For IN, update the Java array with the actual lengths received for each packet.
        if (endpoint & LIBUSB_ENDPOINT_IN) {
            for (int i = 0; i < num_packets; i++) {
                native_packet_lengths[i] = (jint)transfer->iso_packet_desc[i].actual_length;
                total_actual_length += (jint)transfer->iso_packet_desc[i].actual_length;
            }
        } else { // For OUT, just use the total actual_length.
            total_actual_length = (jint)transfer->actual_length;
        }
        result_status = total_actual_length;
    } else {
        __android_log_print(ANDROID_LOG_WARN, APPNAME, "Iso: Transfer completed with status: %s", libusb_error_name(transfer->status));
        result_status = libusb_to_errno(transfer->status);
    }

    if ((endpoint & LIBUSB_ENDPOINT_IN) && result_status >= 0) {
        data_release_mode = 0; // Copy back buffer changes on success
        packet_release_mode = 0; // Copy back packet length changes on success
    }

cleanup_arrays:
    (*env)->ReleaseIntArrayElements(env, iso_packet_lengths, native_packet_lengths, packet_release_mode);
    native_packet_lengths = NULL;
    (*env)->ReleaseByteArrayElements(env, data, (jbyte*)native_buffer, data_release_mode);
    native_buffer = NULL;

cleanup_transfer:
    libusb_free_transfer(transfer);

cleanup_handle:
    if (dev_handle != NULL) {
        libusb_close(dev_handle);
    }

    return result_status;
}
