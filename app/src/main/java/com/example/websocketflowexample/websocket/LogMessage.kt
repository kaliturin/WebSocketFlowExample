package com.example.websocketflowexample.websocket

import android.util.Log

/**
 * Log message holder
 * @param message message text
 * @param priority log priority one of [Log.DEBUG],[Log.INFO],[Log.WARN],[Log.ERROR]
 */
data class LogMessage(
    val message: String,
    val priority: Int = Log.DEBUG
)