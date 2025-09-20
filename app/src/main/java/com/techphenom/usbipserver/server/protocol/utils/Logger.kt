package com.techphenom.usbipserver.server.protocol.utils

import com.techphenom.usbipserver.BuildConfig
import android.util.Log

object Logger {
    private const val TAG_PREFIX = "USB/IP - "

    fun v(tag: String, msg: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (tr == null) Log.v(TAG_PREFIX + tag, msg)
            else Log.v(TAG_PREFIX + tag, msg, tr)
        }
    }

    fun d(tag: String, msg: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (tr == null) Log.d(TAG_PREFIX + tag, msg)
            else Log.d(TAG_PREFIX + tag, msg, tr)
        }
    }

    fun i(tag: String, msg: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (tr == null) Log.i(TAG_PREFIX + tag, msg)
            else Log.i(TAG_PREFIX + tag, msg, tr)
        }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (tr == null) Log.w(TAG_PREFIX + tag, msg)
            else Log.w(TAG_PREFIX + tag, msg, tr)
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr == null) Log.e(TAG_PREFIX + tag, msg)
        else Log.e(TAG_PREFIX + tag, msg, tr)
    }
}