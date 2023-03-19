package com.example.websocketflowexample.websocket.impl

import com.example.websocketflowexample.websocket.WebSocketFlow
import timber.log.Timber

/**
 * An example of simple websocket's response deserializer that actually deserializes just
 * only a throwable
 */
class StringResponseDeserializer : WebSocketFlow.ResponseDeserializer<String> {

    override suspend fun onThrowable(throwable: Throwable): String {
        Timber.e(throwable)
        return throwable.toString()
    }

    override suspend fun fromString(string: String): String? {
        if (isIgnoring(string)) return null
        return string
    }

    // Returns true if ignores a response because of the content
    private fun isIgnoring(string: String): Boolean {
        return string == TEXT_OPEN
    }

    companion object {
        const val TEXT_OPEN = "OPEN"
    }
}