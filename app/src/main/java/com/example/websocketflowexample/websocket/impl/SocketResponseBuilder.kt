package com.example.websocketflowexample.websocket.impl

class SocketResponseBuilder {
    var header: SocketResponse.Header? = null
    var body: SocketResponse.Body? = null

    fun header(type: SocketResponse.Header.Type) = apply {
        header = SocketResponse.Header(type)
    }

    fun body(
        intData: List<Int>? = null,
        strData: List<String>? = null
    ) = apply {
        body = SocketResponse.Body(intData, strData)
    }

    fun build() = SocketResponse(header, body)
}