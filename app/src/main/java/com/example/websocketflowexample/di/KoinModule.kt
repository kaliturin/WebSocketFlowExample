package com.example.websocketflowexample.di

import com.example.websocketflowexample.websocket.WebSocketFlow
import com.example.websocketflowexample.websocket.impl.StringResponseDeserializer
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

object KoinModule {
    fun module() = module {

        // OkHttpClient
        single {
            OkHttpClient.Builder()
                .addInterceptor(
                    HttpLoggingInterceptor().apply {
                        setLevel(HttpLoggingInterceptor.Level.BODY)
                    })
                .build()
        }

        // WebSocket
        single {
            val settings = WebSocketFlow.SocketSettings(
                socketUrl = "ws://192.168.0.10:1337", // local echo server IP (for example https://github.com/websockets/websocket-echo-server)
                silenceTimeoutMills = 20_000L
            )
            val socketFactory = get<OkHttpClient>()
                .newBuilder()
                .readTimeout(settings.readTimeoutMills, TimeUnit.MILLISECONDS)
                .pingInterval(settings.pingTimeoutMills, TimeUnit.MILLISECONDS)
                .build()
            val deserializer = StringResponseDeserializer()
            WebSocketFlow(settings, socketFactory, deserializer)
        }
    }
}