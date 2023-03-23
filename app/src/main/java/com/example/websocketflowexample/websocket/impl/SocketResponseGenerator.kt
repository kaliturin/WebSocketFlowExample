package com.example.websocketflowexample.websocket.impl

import java.util.*

/**
 * Testing SocketResponse generator
 */
class SocketResponseGenerator {
    fun generate(): SocketResponse {
        val r = Random()
        val builder = SocketResponseBuilder()
        if (r.nextBoolean()) {
            fun i() = r.nextInt(10000)
            builder.header(SocketResponse.Header.Type.INT_DATA)
                .body(intData = listOf(i(), i(), i()))
        } else {
            fun s() = UUID.randomUUID().toString()
                .replace("[0-9-]".toRegex(), "").uppercase().take(4)
            builder.header(SocketResponse.Header.Type.STRING_DATA)
                .body(strData = listOf(s(), s(), s()))
        }
        return builder.build()
    }
}