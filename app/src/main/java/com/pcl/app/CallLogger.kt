package com.pcl.app
import android.content.Context
import android.util.Log

/** Lightweight Logcat forwarding for call-control events; no in-app log buffer. */
object CallLogger {
    fun init(context: Context) = Unit
    fun log(message: String) {
        Log.d("PCL-Call", AppLog.redact(message).replace('\n', ' ').take(1000))
    }
    fun clear() = Unit
}
