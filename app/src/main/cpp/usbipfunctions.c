#include <stdlib.h>
#include <jni.h>
#include <pthread.h>

#include <errno.h>
#include <android/log.h>
#include "libusb_src/libusb/libusb.h"

#define APPNAME "UsbIpServerNativeLibusb"
#define MAX_ASYNC_TRANSFERS 256
#define MAX_ATTACHED_DEVICES 16

struct ActiveTransfer {
    int seqNum;
    struct libusb_transfer* transfer;
};
struct AttachedDeviceHandle {
    int fd;
    libusb_device_handle* handle;
};

static libusb_context *g_ctx = NULL;
static pthread_mutex_t g_transferMapMutex;
static pthread_mutex_t g_attachedDevicesMutex;
static pthread_t g_eventThread;
static jobject g_usbLibInstance = NULL;
jmethodID g_onTransferCompletedMethodID = NULL;
JavaVM* g_jvm = NULL;

static volatile int g_keepEventThreadRunning = 0;
static int open_devs = 0;
static struct ActiveTransfer g_activeTransfers[MAX_ASYNC_TRANSFERS];
static struct AttachedDeviceHandle g_attachedDevices[MAX_ATTACHED_DEVICES];

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

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_init(JNIEnv *env, jobject thiz) {
    if (g_ctx != NULL) {
        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Libusb context already initialized.");
        return 0;
    }

    if (g_jvm == NULL) (*env)->GetJavaVM(env, &g_jvm);
    if (g_onTransferCompletedMethodID == NULL) {
        jclass clazz = (*env)->GetObjectClass(env, thiz);
        g_onTransferCompletedMethodID = (*env)->GetMethodID(env, clazz, "onTransferCompleted", "(IIII[I[I)V");

        if (g_onTransferCompletedMethodID == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Fatal: Could not find onTransferCompleted method!");
            return -1;
        }
    }

    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int r = libusb_init_context(&g_ctx, NULL, 0);
    if (r < 0) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "libusb_init failed: %s", libusb_error_name(r));
        g_ctx = NULL;
        return libusb_to_errno(r);
    }

    pthread_mutex_init(&g_transferMapMutex, NULL);
    pthread_mutex_init(&g_attachedDevicesMutex, NULL);
    for (int i = 0; i < MAX_ASYNC_TRANSFERS; i++) {
        g_activeTransfers[i].seqNum = -1; // -1 flag for empty slot
        g_activeTransfers[i].transfer = NULL;
    }
    for (int i = 0; i < MAX_ATTACHED_DEVICES; i++) {
        g_attachedDevices[i].fd = -1; // -1 flag for empty slot
    }

    if (g_usbLibInstance == NULL) {
        g_usbLibInstance = (*env)->NewGlobalRef(env, thiz);
    }

    __android_log_print(ANDROID_LOG_INFO, APPNAME, "Libusb context initialized successfully.");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_exit(JNIEnv *env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, APPNAME, "Exit requested. Cleaning up...");

    if (g_keepEventThreadRunning) {
        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Stopping background event thread...");
        g_keepEventThreadRunning = 0;

        if (g_ctx != NULL) {
            libusb_interrupt_event_handler(g_ctx);
        }

        pthread_join(g_eventThread, NULL);
        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Background event thread stopped.");
    }

    pthread_mutex_lock(&g_transferMapMutex);
    int active_count = 0;
    for (int i = 0; i < MAX_ASYNC_TRANSFERS; i++) {
        if (g_activeTransfers[i].transfer != NULL) {
            libusb_cancel_transfer(g_activeTransfers[i].transfer);
            active_count++;
        }
    }
    pthread_mutex_unlock(&g_transferMapMutex);

    if (active_count > 0) {
        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Cancelled %d active transfers.", active_count);
    }

    int timeout_ms = 500; // 500ms
    while (timeout_ms > 0) {
        pthread_mutex_lock(&g_transferMapMutex);
        int remaining_transfers = 0;
        for (int i = 0; i < MAX_ASYNC_TRANSFERS; i++) {
            if (g_activeTransfers[i].transfer != NULL) {
                remaining_transfers++;
            }
        }
        pthread_mutex_unlock(&g_transferMapMutex);

        if (remaining_transfers == 0) {
            __android_log_print(ANDROID_LOG_INFO, APPNAME, "All active transfers have been reaped.");
            break;
        }

        struct timeval tv = {0, 10000}; // Wait for up to 10ms for an event.
        libusb_handle_events_timeout(g_ctx, &tv);
        timeout_ms -= 10;
    }

    if (timeout_ms <= 0) {
        __android_log_print(ANDROID_LOG_WARN, APPNAME, "Exit timeout! Some transfers may not have been reaped.");
    }

    pthread_mutex_lock(&g_attachedDevicesMutex);
    for (int i = 0; i < MAX_ATTACHED_DEVICES; i++) {
        if (g_attachedDevices[i].handle != NULL) {
            __android_log_print(ANDROID_LOG_WARN, APPNAME, "Force closing orphan device handle for fd %d", g_attachedDevices[i].fd);
            libusb_close(g_attachedDevices[i].handle);
            g_attachedDevices[i].handle = NULL;
            g_attachedDevices[i].fd = -1;
        }
    }
    open_devs = 0;
    pthread_mutex_unlock(&g_attachedDevicesMutex);

    if (g_usbLibInstance != NULL) {
        (*env)->DeleteGlobalRef(env, g_usbLibInstance);
        g_usbLibInstance = NULL;
    }

    if (g_ctx != NULL) {
        libusb_exit(g_ctx);
        g_ctx = NULL;
        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Libusb context de-initialized.");
    }
    pthread_mutex_destroy(&g_transferMapMutex);
    pthread_mutex_destroy(&g_attachedDevicesMutex);
}

void *event_thread_func(void *arg) {
    __android_log_print(ANDROID_LOG_INFO, APPNAME, "Event handling thread started");

    JNIEnv *env;
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_6;
    args.name = "LibusbEventThread";
    args.group = NULL;

    int attach_result = (*g_jvm)->AttachCurrentThread(g_jvm, &env, &args);
    if (attach_result != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Failed to attach event thread to JVM");
    }

    while (g_keepEventThreadRunning) {
        struct timeval tv = {1, 0};
        int r = libusb_handle_events_timeout(g_ctx, &tv);
        if (r < 0) {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Event thread: libusb_handle_events failed: %s", libusb_error_name(r));
            if (r == LIBUSB_ERROR_NO_DEVICE) break;
        }
    }

    if (attach_result == JNI_OK) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    __android_log_print(ANDROID_LOG_INFO, APPNAME, "Event handling thread exiting");
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_openDeviceHandle(JNIEnv *env,
                                                                            jobject thiz, jint fd) {
    if (g_ctx == NULL) return -EFAULT;
    libusb_device_handle *dev_handle = NULL;

    int r = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &dev_handle);
    if (r < 0 || dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Failed to wrap device for fd %d: %s", fd, libusb_error_name(r));
        return libusb_to_errno(r);
    }

    pthread_mutex_lock(&g_attachedDevicesMutex);
    int slot = -1;
    for (int i = 0; i < MAX_ATTACHED_DEVICES; i++) {
        if (g_attachedDevices[i].fd == -1) {
            g_attachedDevices[i].fd = fd;
            g_attachedDevices[i].handle = dev_handle;
            slot = i;
            break;
        }
    }

    if (slot == -1) {
        pthread_mutex_unlock(&g_attachedDevicesMutex);
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "No free slots to store device handle for fd %d", fd);
        libusb_close(dev_handle);
        return -EBUSY;
    }

    open_devs++;
    if (open_devs == 1) {
        g_keepEventThreadRunning = 1;
        pthread_create(&g_eventThread, NULL, event_thread_func, NULL);
    }

    pthread_mutex_unlock(&g_attachedDevicesMutex);

    __android_log_print(ANDROID_LOG_INFO, APPNAME, "Successfully opened and stored handle for fd %d", fd);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_closeDeviceHandle(JNIEnv *env,
                                                                             jobject thiz,
                                                                             jint fd) {
    libusb_device_handle *handle_to_close = NULL;
    int stop_thread = 0;

    pthread_mutex_lock(&g_attachedDevicesMutex);
    for (int i = 0; i < MAX_ATTACHED_DEVICES; i++) {
        if (g_attachedDevices[i].fd == fd) {
            handle_to_close = g_attachedDevices[i].handle;
            g_attachedDevices[i].fd = -1;
            g_attachedDevices[i].handle = NULL;
            break;
        }
    }

    if (handle_to_close != NULL) {
        open_devs--;
        if (open_devs == 0) {
            stop_thread = 1;
            g_keepEventThreadRunning = 0;
        }
    }
    pthread_mutex_unlock(&g_attachedDevicesMutex);

    if (handle_to_close) {
        libusb_close(handle_to_close);
        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Closed handle for fd %d", fd);
    }

    if (stop_thread) {
        if (g_ctx != NULL) {
            libusb_interrupt_event_handler(g_ctx);
        }
        pthread_join(g_eventThread, NULL);
    }

    return 0;
}

void LIBUSB_CALL generic_transfer_cb(struct libusb_transfer *transfer) {
    int seqNum = (int)(intptr_t)transfer->user_data;
    int totalActualLength = transfer->actual_length;

    pthread_mutex_lock(&g_transferMapMutex);
    for (int i = 0; i < MAX_ASYNC_TRANSFERS; i++) {
        if (g_activeTransfers[i].seqNum == seqNum) {
            g_activeTransfers[i].seqNum = -1;
            g_activeTransfers[i].transfer = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&g_transferMapMutex);

    JNIEnv* env;
    int needs_detach = 0;

    int get_env_result = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);

    if (get_env_result == JNI_EDETACHED) { // This is a fallback case.
        __android_log_print(ANDROID_LOG_WARN, APPNAME, "Callback thread was not attached, attaching now...");
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Callback failed to attach to JVM!");
            libusb_free_transfer(transfer);
            return;
        }
        needs_detach = 1;
    } else if (get_env_result != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "Callback GetEnv failed with error %d", get_env_result);
        libusb_free_transfer(transfer);
        return;
    }

    jintArray iso_actual_lengths = NULL;
    jintArray iso_packet_statuses = NULL;
    if (transfer->type == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
        int num_packets = transfer->num_iso_packets;
        iso_actual_lengths = (*env)->NewIntArray(env, num_packets);
        iso_packet_statuses = (*env)->NewIntArray(env, num_packets);

        if (iso_actual_lengths != NULL) {
            jint *temp_lengths = (jint *) malloc(num_packets * sizeof(jint));
            jint *temp_statuses = (jint *) malloc(num_packets * sizeof(jint));

            if (temp_lengths != NULL && temp_statuses != NULL) {
                for (int i = 0; i < num_packets; i++) {
                    temp_lengths[i] = (jint) transfer->iso_packet_desc[i].actual_length;
                    totalActualLength += temp_lengths[i];

                    int packet_status = transfer->iso_packet_desc[i].status;
                    temp_statuses[i] = (jint) libusb_to_errno(packet_status);
                }

                (*env)->SetIntArrayRegion(env, iso_actual_lengths, 0, num_packets, temp_lengths);
                (*env)->SetIntArrayRegion(env, iso_packet_statuses, 0, num_packets, temp_statuses);
                free(temp_lengths);
                free(temp_statuses);
            }
        }
    }

    (*env)->CallVoidMethod(env, g_usbLibInstance, g_onTransferCompletedMethodID,
                           seqNum,
                           libusb_to_errno(transfer->status),
                           (jint)totalActualLength,
                           (jint)transfer->type,
                           iso_actual_lengths,
                           iso_packet_statuses);

    if (iso_actual_lengths != NULL) {
        (*env)->DeleteLocalRef(env, iso_actual_lengths);
    }
    if (iso_packet_statuses != NULL) {
        (*env)->DeleteLocalRef(env, iso_packet_statuses);
    }
    libusb_free_transfer(transfer);
    if (needs_detach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
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
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_doControlTransferAsync(JNIEnv *env,
                                                                                  jobject thiz,
                                                                                  jint fd,
                                                                                  jobject buffer,
                                                                                  jint timeout,
                                                                                  jint seqNum) {
    libusb_device_handle *dev_handle = NULL;
    struct libusb_transfer *transfer = NULL;
    unsigned char *native_buffer = NULL;
    int r;

    if (g_ctx == NULL) return -EFAULT;

    pthread_mutex_lock(&g_attachedDevicesMutex);
    for (int i = 0; i < MAX_ATTACHED_DEVICES; i++) {
        if (g_attachedDevices[i].fd == fd) {
            dev_handle = g_attachedDevices[i].handle;
            break;
        }
    }
    pthread_mutex_unlock(&g_attachedDevicesMutex);

    if (dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncCtrl: No stored handle found for fd %d", fd);
        return -ENODEV;
    }

    native_buffer = (unsigned char *)(*env)->GetDirectBufferAddress(env, buffer);
    if (native_buffer == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncCtrl: Buffer is not direct!");
        return -EFAULT;
    }

    transfer = libusb_alloc_transfer(0);
    if (!transfer) return -ENOMEM;

    libusb_fill_control_transfer(
            transfer,
            dev_handle,
            native_buffer,
            generic_transfer_cb,
            (void*)(intptr_t)seqNum,
            (unsigned int)timeout
    );

    int slot = -1;
    pthread_mutex_lock(&g_transferMapMutex);
    for (int i = 0; i < MAX_ASYNC_TRANSFERS; i++) {
        if (g_activeTransfers[i].seqNum == -1) {
            g_activeTransfers[i].seqNum = seqNum;
            g_activeTransfers[i].transfer = transfer;
            slot = i;
            break;
        }
    }
    pthread_mutex_unlock(&g_transferMapMutex);

    if (slot == -1) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncCtrl: No free slots!");
        libusb_free_transfer(transfer);
        return -EBUSY;
    }

    r = libusb_submit_transfer(transfer);
    if (r < 0) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncCtrl: libusb_submit_transfer failed: %s", libusb_error_name(r));

        pthread_mutex_lock(&g_transferMapMutex);
        g_activeTransfers[slot].seqNum = -1;
        g_activeTransfers[slot].transfer = NULL;
        pthread_mutex_unlock(&g_transferMapMutex);

        libusb_free_transfer(transfer);
        return libusb_to_errno(r);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_doBulkTransferAsync(JNIEnv *env,
                                                                               jobject thiz,
                                                                               jint fd,
                                                                               jint endpoint,
                                                                               jobject buffer,
                                                                               jint timeout,
                                                                               jint seqNum) {
    libusb_device_handle *dev_handle = NULL;
    struct libusb_transfer *transfer = NULL;
    unsigned char *native_buffer = NULL;
    int r;

    if (g_ctx == NULL) return -EFAULT;

    pthread_mutex_lock(&g_attachedDevicesMutex);
    for (int i = 0; i < MAX_ATTACHED_DEVICES; i++) {
        if (g_attachedDevices[i].fd == fd) {
            dev_handle = g_attachedDevices[i].handle;
            break;
        }
    }
    pthread_mutex_unlock(&g_attachedDevicesMutex);

    if (dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncBulk: No stored handle found for fd %d!", fd);
        return -ENODEV;
    }

    native_buffer = (unsigned char *)(*env)->GetDirectBufferAddress(env, buffer);
    if (native_buffer == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncBulk: Buffer is not direct!");
        return -EFAULT;
    }

    transfer = libusb_alloc_transfer(0);
    if (!transfer) return -ENOMEM;

    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);

    libusb_fill_bulk_transfer(
            transfer,
            dev_handle,
            (unsigned char)endpoint,
            native_buffer,
            (int)capacity,
            generic_transfer_cb,
            (void*)(intptr_t)seqNum,
            (unsigned int)timeout
    );

    int slot = -1;
    pthread_mutex_lock(&g_transferMapMutex);
    for (int i = 0; i < MAX_ASYNC_TRANSFERS; i++) {
        if (g_activeTransfers[i].seqNum == -1) {
            g_activeTransfers[i].seqNum = seqNum;
            g_activeTransfers[i].transfer = transfer;
            slot = i;
            break;
        }
    }
    pthread_mutex_unlock(&g_transferMapMutex);

    if (slot == -1) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncBulk: No free slots for active transfer!");
        libusb_free_transfer(transfer);
        return -EBUSY;
    }

    r = libusb_submit_transfer(transfer);
    if (r < 0) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncBulk: libusb_submit_transfer failed: %s", libusb_error_name(r));

        pthread_mutex_lock(&g_transferMapMutex);
        g_activeTransfers[slot].seqNum = -1;
        g_activeTransfers[slot].transfer = NULL;
        pthread_mutex_unlock(&g_transferMapMutex);

        libusb_free_transfer(transfer);
        return libusb_to_errno(r);
    }

    return 0;
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_doInterruptTransferAsync(JNIEnv *env,
                                                                                    jobject thiz,
                                                                                    jint fd,
                                                                                    jint endpoint,
                                                                                    jobject buffer,
                                                                                    jint timeout,
                                                                                    jint seqNum) {
    libusb_device_handle *dev_handle = NULL;
    struct libusb_transfer *transfer = NULL;
    unsigned char *native_buffer = NULL;
    int r;

    if (g_ctx == NULL) return -EFAULT;

    pthread_mutex_lock(&g_attachedDevicesMutex);
    for (int i = 0; i < MAX_ATTACHED_DEVICES; i++) {
        if (g_attachedDevices[i].fd == fd) {
            dev_handle = g_attachedDevices[i].handle;
            break;
        }
    }
    pthread_mutex_unlock(&g_attachedDevicesMutex);
    if (dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncIntr: No stored handle found for fd %d!", fd);
        return -ENODEV;
    }

    native_buffer = (unsigned char *)(*env)->GetDirectBufferAddress(env, buffer);
    if (native_buffer == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncIntr: Buffer is not direct!");
        return -EFAULT;
    }

    transfer = libusb_alloc_transfer(0);
    if (!transfer) return -ENOMEM;

    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);

    libusb_fill_interrupt_transfer(
            transfer,
            dev_handle,
            (unsigned char)endpoint,
            native_buffer,
            (int)capacity,
            generic_transfer_cb,
            (void*)(intptr_t)seqNum,
            (unsigned int)timeout
    );

    int slot = -1;
    pthread_mutex_lock(&g_transferMapMutex);
    for (int i = 0; i < MAX_ASYNC_TRANSFERS; i++) {
        if (g_activeTransfers[i].seqNum == -1) {
            g_activeTransfers[i].seqNum = seqNum;
            g_activeTransfers[i].transfer = transfer;
            slot = i;
            break;
        }
    }
    pthread_mutex_unlock(&g_transferMapMutex);

    if (slot == -1) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncIntr: No free slots for active transfer!");
        libusb_free_transfer(transfer);
        return -EBUSY;
    }

    r = libusb_submit_transfer(transfer);
    if (r < 0) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncIntr: libusb_submit_transfer failed: %s", libusb_error_name(r));

        pthread_mutex_lock(&g_transferMapMutex);
        g_activeTransfers[slot].seqNum = -1;
        g_activeTransfers[slot].transfer = NULL;
        pthread_mutex_unlock(&g_transferMapMutex);

        libusb_free_transfer(transfer);
        return libusb_to_errno(r);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_doIsochronousTransferAsync(JNIEnv *env,
                                                                                      jobject thiz,
                                                                                      jint fd,
                                                                                      jint endpoint,
                                                                                      jobject buffer,
                                                                                      jintArray iso_packet_lengths,
                                                                                      jint seqNum) {
    libusb_device_handle *dev_handle = NULL;
    struct libusb_transfer *transfer = NULL;
    unsigned char *native_buffer = NULL;
    jint *native_packet_lengths = NULL;
    int r;

    pthread_mutex_lock(&g_attachedDevicesMutex);
    for (int i = 0; i < MAX_ATTACHED_DEVICES; i++) {
        if (g_attachedDevices[i].fd == fd) {
            dev_handle = g_attachedDevices[i].handle;
            break;
        }
    }
    pthread_mutex_unlock(&g_attachedDevicesMutex);

    if (dev_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncIntr: No stored handle found for fd %d!", fd);
        return -ENODEV;
    }

    native_buffer = (unsigned char *)(*env)->GetDirectBufferAddress(env, buffer);
    if (native_buffer == NULL) {
        return -EFAULT;
    }

    jsize num_packets = (*env)->GetArrayLength(env, iso_packet_lengths);
    jlong total_length = (*env)->GetDirectBufferCapacity(env, buffer);

    transfer = libusb_alloc_transfer(num_packets);
    if (!transfer) return -ENOMEM;

    native_packet_lengths = (jint*)(*env)->GetPrimitiveArrayCritical(env, iso_packet_lengths, NULL);
    if (native_packet_lengths == NULL) {
        libusb_free_transfer(transfer);
        return -ENOMEM;
    }

    libusb_fill_iso_transfer(transfer,
                             dev_handle,
                             (unsigned char)endpoint,
                             native_buffer,
                             (int)total_length,
                             num_packets,
                             generic_transfer_cb,
                             (void*)(intptr_t)seqNum,
                             1000);
    for (int i = 0; i < num_packets; i++) {
        transfer->iso_packet_desc[i].length = native_packet_lengths[i];
    }
    (*env)->ReleasePrimitiveArrayCritical(env, iso_packet_lengths, native_packet_lengths, JNI_ABORT);

    int slot = -1;
    pthread_mutex_lock(&g_transferMapMutex);
    for (int i = 0; i < MAX_ASYNC_TRANSFERS; i++) {
        if (g_activeTransfers[i].seqNum == -1) {
            g_activeTransfers[i].seqNum = seqNum;
            g_activeTransfers[i].transfer = transfer;
            slot = i;
            break;
        }
    }
    pthread_mutex_unlock(&g_transferMapMutex);

    if (slot == -1) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncIso: No free slots for active transfer!");
        libusb_free_transfer(transfer);
        return -EBUSY;
    }

    r = libusb_submit_transfer(transfer);
    if (r < 0) {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, "AsyncIso: libusb_submit_transfer failed: %s", libusb_error_name(r));

        pthread_mutex_lock(&g_transferMapMutex);
        g_activeTransfers[slot].seqNum = -1;
        g_activeTransfers[slot].transfer = NULL;
        pthread_mutex_unlock(&g_transferMapMutex);

        libusb_free_transfer(transfer);
        return libusb_to_errno(r);
    }

    return 0;
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_cancelTransfer(JNIEnv *env, jobject thiz,
                                                                          jint seq_num) {
    struct libusb_transfer *transfer_to_cancel = NULL;
    int r = -1;

    pthread_mutex_lock(&g_transferMapMutex);
    for (int i = 0; i < MAX_ASYNC_TRANSFERS; i++) {
        if (g_activeTransfers[i].seqNum == seq_num) {
            transfer_to_cancel = g_activeTransfers[i].transfer;
            break;
        }
    }
    pthread_mutex_unlock(&g_transferMapMutex);

    if (transfer_to_cancel != NULL) {
        r = libusb_cancel_transfer(transfer_to_cancel);

        if (r < 0) {
            __android_log_print(ANDROID_LOG_ERROR, APPNAME,
                                "Failed to cancel transfer seqNum %d: %s", seq_num, libusb_error_name(r));
            return libusb_to_errno(r);
        }

        __android_log_print(ANDROID_LOG_INFO, APPNAME, "Successfully requested cancellation for seqNum %d", seq_num);
        return 0;
    }

    return -ENOENT;
}