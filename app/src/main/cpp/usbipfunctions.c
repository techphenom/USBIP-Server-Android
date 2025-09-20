#include <stdlib.h>
#include <unistd.h>
#include <jni.h>

#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <android/log.h>

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
    jbyte* dataPtr = data ? (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL) : NULL;

    struct usbdevfs_ctrltransfer xfer = {
            .bRequestType = request_type,
            .bRequest = request,
            .wValue = value,
            .wIndex = index,
            .wLength = length,
            .timeout = timeout,
            .data = dataPtr,
    };
    jint res = TEMP_FAILURE_RETRY(ioctl(fd, USBDEVFS_CONTROL, &xfer));
    if (res < 0) {
        res = -errno;
    }

    // If this is an OUT or a failed IN, use JNI_ABORT to avoid a useless memcpy().
    if (dataPtr) {
        (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr,
                                              ((request_type & 0x80) && (res > 0)) ? 0 : JNI_ABORT);
    }

    return res;
}

JNIEXPORT jint JNICALL
Java_com_techphenom_usbipserver_server_protocol_usb_UsbLib_doBulkTransfer(JNIEnv *env,
                                                                        jobject thiz,
                                                                        jint fd,
                                                                        jint endpoint,
                                                                        jbyteArray data,
                                                                        jint timeout) {
    jbyte* dataPtr = data ? (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL) : NULL;
    jsize dataLen = data ? (*env)->GetArrayLength(env, data) : 0;

    struct usbdevfs_bulktransfer xfer = {
            .ep = endpoint,
            .len = dataLen,
            .timeout = timeout,
            .data = dataPtr,
    };
    jint res = TEMP_FAILURE_RETRY(ioctl(fd, USBDEVFS_BULK, &xfer));
    if (res < 0) {
        res = -errno;
    }

    // If this is an OUT or a failed IN, use JNI_ABORT to avoid a useless memcpy().
    if (dataPtr) {
        (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr,
                                              ((endpoint & 0x80) && (res > 0)) ? 0 : JNI_ABORT);
    }

    return res;
}