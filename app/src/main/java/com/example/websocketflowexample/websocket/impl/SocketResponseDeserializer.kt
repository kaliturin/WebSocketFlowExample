package com.example.websocketflowexample.websocket.impl

import com.example.websocketflowexample.utils.JsonUtils
import com.example.websocketflowexample.websocket.WebSocketFlow

/**
 * An example of simple websocket's response deserializer
 */
class SocketResponseDeserializer : WebSocketFlow.ResponseDeserializer<SocketResponse> {
    override suspend fun onThrowable(throwable: Throwable): SocketResponse {
        return SocketResponse(error = throwable)
    }

    override suspend fun fromString(string: String): SocketResponse? {
        return try {
            JsonUtils.fromJson(string, SocketResponse::class)
        } catch (e: Exception) {
            SocketResponse(error = e)
        }
    }
}