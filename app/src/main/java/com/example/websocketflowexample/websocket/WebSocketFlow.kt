package com.example.websocketflowexample.websocket

import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.websocketflowexample.websocket.WebSocketFlow.ResponseDeserializer
import com.example.websocketflowexample.websocket.WebSocketFlow.SocketCloseCode.*
import com.example.websocketflowexample.websocket.WebSocketFlow.SocketState.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.*
import okio.ByteString
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [kotlinx.coroutines.flow.SharedFlow] based WebSocket implementation that can manage connections lifecycle.
 * @param settings socket url and timeouts
 * @param socketFactory implementation of [WebSocket.Factory] interface for socket building
 * @param responseDeserializer implementation of [ResponseDeserializer] interface for deserialization socket's responses
 * @param coroutineScope socket's coroutine scope (supposing with +Dispatchers.IO)
 */
@Suppress("MemberVisibilityCanBePrivate")
open class WebSocketFlow<T>(
    private val settings: SocketSettings,
    private val socketFactory: WebSocket.Factory,
    private val responseDeserializer: ResponseDeserializer<T>,
    private val coroutineScope: CoroutineScope = defCoroutineScope,
) : WebSocketListener() {

    private val socketRequest = Request.Builder().url(settings.socketUrl).build()
    private val clientsCounter = AtomicInteger(0)
    private val socketStateRef = AtomicReference(STATE_CLOSED)
    private val socketConnectingMutex = Mutex()
    private val socketConnectingJobRef = AtomicReference<Job>()
    private val clientsCountingJobRef = AtomicReference<Job>()
    private val socketCheckSilenceJobRef = AtomicReference<Job>()
    private val socketRef = AtomicReference<WebSocket>()
    private val socketFlow = MutableSharedFlow<T>()
    private val loggerFlow = MutableSharedFlow<LogMessage>()

    /**
     * Current socket state. Sometimes it's useful to know it when you want to send something
     */
    var socketState: SocketState
        private set(value) {
            socketStateRef.set(value)
        }
        get() = socketStateRef.get()

    private var socket: WebSocket?
        set(value) {
            socketRef.set(value)
        }
        get() = socketRef.get()

    init {
        startClientsCounting()
    }

    /**
     * @return socket's flow for data collection
     */
    fun flow(): Flow<T> = socketFlow

    /**
     * Listens the socket's flow for data collection
     */
    suspend fun listen(collector: FlowCollector<T>) = flow().collect(collector)

    /**
     * Listens the socket's flow for data collection
     */
    fun listen(coroutineScope: CoroutineScope, collector: FlowCollector<T>) {
        coroutineScope.launch {
            flow().collect(collector)
        }
    }

    /**
     * Pauses and closes the socket with shutdown code. For reopening - use [resume]
     */
    fun close() {
        pause()
        close(CODE_SHUTDOWN)
    }

    /**
     * Pauses the socket
     */
    fun pause() {
        logI("Pausing socket...")
        stopConnecting()
        socketState = STATE_PAUSED
    }

    /**
     * Resumes the socket if there are some subscribers else starts connecting
     */
    fun resume() {
        if (socketState in arrayOf(STATE_OPENED, STATE_CONNECTING)) return
        logI("Resuming socket...")
        if (socket == null) {
            socketState = STATE_CLOSED
            startConnecting()
        } else {
            socketState = STATE_OPENED
        }
    }

    /**
     * Sends data with the socket if it's in OPENED state
     * @return true on success
     */
    fun send(data: String): Boolean {
        return if (socketState != STATE_OPENED) false else
            socket?.send(data) ?: false
    }

    /**
     * Listens the socket's log flow for log messages collection
     */
    fun listenLog(coroutineScope: CoroutineScope, collector: FlowCollector<LogMessage>) {
        coroutineScope.launch {
            loggerFlow.collect(collector)
        }
    }

    private fun startConnecting() {
        if (!needToConnect()) return
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                if (socketConnectingMutex.tryLock()) {
                    try {
                        if (!needToConnect()) return@withContext
                        socketState = STATE_CONNECTING
                        logI("Start connecting...")
                        val job = timerFlow(
                            settings.connectTimeoutMills, settings.connectInitDelayMills
                        ).onEach {
                            if (socketState != STATE_OPENED) {
                                onConnecting()
                            } else {
                                logI("Connection is already opened")
                                stopConnecting()
                                startSilenceTimer()
                            }
                        }.launchIn(this)
                        socketConnectingJobRef.getAndSet(job)?.cancel()
                    } finally {
                        socketConnectingMutex.unlock()
                    }
                }
            }
        }
    }

    private fun stopConnecting() {
        if (socketState == STATE_CONNECTING) logI("Stop connecting")
        socketConnectingJobRef.getAndSet(null)?.cancel()
    }

    private fun close(code: SocketCloseCode) {
        if (socketState == STATE_CLOSED) return
        stopSilenceTimer()
        stopConnecting()
        if (socket != null) {
            logI("Closing socket with code %s...", code)
            socketRef.getAndSet(null)
                ?.close(code.code, "close() was called")
        }
        socketState = STATE_CLOSED
    }

    private fun onConnecting() {
        if (socket == null) {
            logI("Creating socket...")
            socket = socketFactory.newWebSocket(socketRequest, this@WebSocketFlow)
        } else {
            // else the socket is probably created and is waiting for onOpen/onFailure response
            logI("Waiting for server response...")
        }
    }

    private fun onFailure(throwable: Throwable) {
        if (socketState == STATE_PAUSED) return
        coroutineScope.launch {
            responseDeserializer.onThrowable(throwable)?.let {
                socketFlow.emit(it) // send to clients
            }
        }
    }

    private fun onMessage(string: String) {
        if (socketState == STATE_PAUSED) return
        coroutineScope.launch {
            responseDeserializer.fromString(string)?.let {
                socketFlow.emit(it) // send to clients
            }
        }
    }

    private fun onMessage(byteString: ByteString) {
        if (socketState == STATE_PAUSED) return
        coroutineScope.launch {
            responseDeserializer.fromByteString(byteString)?.let {
                socketFlow.emit(it) // send to clients
            }
        }
    }

    private fun startSilenceTimer() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val job = timerFlow(
                    settings.silenceTimeoutMills, settings.silenceTimeoutMills
                ).onEach {
                    logW("Silence timer has triggered")
                    close(CODE_SILENCE_TIMER)
                }.launchIn(this)
                socketCheckSilenceJobRef.getAndSet(job)?.cancel()
            }
        }
    }

    private fun stopSilenceTimer() {
        socketCheckSilenceJobRef.getAndSet(null)?.cancel()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        logI("onOpen: response = %s", response.message)
        socketState = STATE_OPENED
        stopConnecting()
        startSilenceTimer()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        logW("onClosed: code = %d, reason = %s", code, reason)
        socketState = STATE_CLOSED
        socket = null
        if (SocketCloseCode.fromInt(code) in arrayOf(CODE_NO_CLIENTS, CODE_SHUTDOWN))
            stopSilenceTimer()
        else
            startConnecting()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        logE("onFailure: error = %s", t.toString())
        onFailure(t)
        socket = null
        if (socketState !in arrayOf(STATE_CONNECTING, STATE_PAUSED)) {
            socketState = STATE_CLOSED
            startConnecting()
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        log("onMessage: byte string size = %d", bytes.size)
        onMessage(bytes)
        startSilenceTimer()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        log("onMessage: %s", text)
        onMessage(text)
        startSilenceTimer()
    }

    private fun needToConnect(): Boolean {
        return clientsCounter.get() > 0 && socketState == STATE_CLOSED
    }

    private suspend fun timerFlow(period: Long, initialDelay: Long = 0) = flow {
        delay(initialDelay)
        while (true) {
            emit(Unit)
            delay(period)
        }
    }

    private fun startClientsCounting() {
        val job = socketFlow
            .subscriptionCount
            .onEach { newCount ->
                val oldCount = clientsCounter.getAndSet(newCount)
                if (newCount > oldCount)
                    logI("Client %d subscribed", newCount)
                else if (newCount < oldCount)
                    logI("Client %d unsubscribed", oldCount)
                // there is no any client - close the connection, else resume
                if (newCount == 0)
                    close(CODE_NO_CLIENTS)
                else
                    resume()
            }.launchIn(coroutineScope)
        clientsCountingJobRef.getAndSet(job)?.cancel()
    }

    private fun log(priority: Int, message: String, vararg args: Any?) {
        val formatted = formatLog(message, *args)
        Timber.log(priority, formatted)
        coroutineScope.launch {
            loggerFlow.emit(LogMessage(formatted, priority))
        }
    }

    private fun log(message: String, vararg args: Any?) = log(Log.DEBUG, message, *args)
    private fun logI(message: String, vararg args: Any?) = log(Log.INFO, message, *args)

    @Suppress("SameParameterValue")
    private fun logW(message: String, vararg args: Any?) = log(Log.WARN, message, *args)

    @Suppress("SameParameterValue")
    private fun logE(message: String, vararg args: Any?) = log(Log.ERROR, message, *args)

    private fun formatLog(message: String?, vararg args: Any?) =
        String.format("t${Thread.currentThread().id} $message", *args)

    /**
     * Socket's state
     */
    enum class SocketState {
        STATE_CONNECTING, // socket is connecting
        STATE_OPENED,     // socket is opened
        STATE_PAUSED,     // socket is paused for connecting and data receiving/sending
        STATE_CLOSED      // socket is closed
    }

    private enum class SocketCloseCode(val code: Int) {
        CODE_NO_CLIENTS(1001),
        CODE_SILENCE_TIMER(1002),
        CODE_SHUTDOWN(1003);

        override fun toString(): String {
            return "$name($code)"
        }

        companion object {
            fun fromInt(code: Int): SocketCloseCode? {
                for (id in values()) {
                    if (id.code == code) return id
                }
                return null
            }
        }
    }

    /**
     * Deserializes socket's responses
     */
    interface ResponseDeserializer<T> {
        /**
         * Deserializes an error of socket response
         * @return deserialized response object or null if the response is ignoring
         */
        suspend fun onThrowable(throwable: Throwable): T?

        /**
         * Deserializes a string content of socket response
         * @return deserialized response object or null if the response is ignoring
         */
        suspend fun fromString(string: String): T?

        /**
         * Deserializes a ByteString content of socket response
         * @return deserialized response object or null if the response is ignoring
         */
        suspend fun fromByteString(bytes: ByteString): T? = null
    }

    data class LogMessage(
        val message: String,
        val priority: Int = Log.DEBUG
    )

    /**
     * WebSocket's settings
     */
    class SocketSettings(
        val socketUrl: String,
        val connectTimeoutMills: Long = CONNECT_TIMEOUT_MILLS,
        val connectInitDelayMills: Long = CONNECT_INIT_DELAY_MILLS,
        val silenceTimeoutMills: Long = SILENCE_TIMEOUT_MILLS,
        val readTimeoutMills: Long = READ_TIMEOUT_MILLS,
        val pingTimeoutMills: Long = PING_TIMEOUT_MILLS
    )

    companion object {
        private const val CONNECT_TIMEOUT_MILLS = 2_000L
        private const val CONNECT_INIT_DELAY_MILLS = 2_000L
        private const val SILENCE_TIMEOUT_MILLS = 60_000L
        private const val READ_TIMEOUT_MILLS = 30_000L
        private const val PING_TIMEOUT_MILLS = 10_000L

        private val defCoroutineScope: CoroutineScope
            get() = ProcessLifecycleOwner.get().lifecycleScope + Dispatchers.IO
    }
}