package com.example.websocketflowexample.websocket

import android.util.Log
import androidx.lifecycle.LifecycleOwner
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
import java.util.concurrent.ConcurrentHashMap
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
class WebSocketFlow<T>(
    private val settings: SocketSettings,
    private val socketFactory: WebSocket.Factory,
    private val responseDeserializer: ResponseDeserializer<T>,
    private val coroutineScope: CoroutineScope = defCoroutineScope,
) : WebSocketListener() {

    private val socketRequest = Request.Builder().url(settings.socketUrl).build()
    private val clientsCounter = AtomicInteger(0)
    private val socketStateRef = AtomicReference(STATE_CLOSED)
    private val socketConnectingMutex = Mutex()
    private val lifecycleOwnerSubscriptions = ConcurrentHashMap<Int, Job>()
    private val socketConnectingJobRef = AtomicReference<Job>()
    private val clientsCountingJobRef = AtomicReference<Job>()
    private val socketCheckSilenceJobRef = AtomicReference<Job>()
    private val socketRef = AtomicReference<WebSocket>()
    private val socketFlow = MutableSharedFlow<T>()
    private val loggerFlow = MutableSharedFlow<LogMessage>()

    /**
     * Socket's current state
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
     * @return socket's flow for a data collection
     */
    fun flow(): Flow<T> = socketFlow

    /**
     * Subscribes for listening to the socket's flow for a data collection
     */
    suspend fun subscribe(collector: FlowCollector<T>) = flow().collect(collector)

    /**
     * Subscribes for listening to the socket's flow for a data collection
     */
    fun subscribe(coroutineScope: CoroutineScope, collector: FlowCollector<T>): Job {
        return coroutineScope.launch { subscribe(collector) }
    }

    /**
     * Subscribes a lifecycle owner for listening to the socket's flow for a data collection.
     * Allows to escape repeating subscriptions with the same lifecycle owner.
     * This method is recommended to use in common case when single subscription per lifecycle
     * owner is supposed to be.
     */
    fun subscribe(lifecycleOwner: LifecycleOwner, collector: FlowCollector<T>): Job {
        val id = lifecycleOwner.hashCode()
        val job = lifecycleOwnerSubscriptions[id]
        return if (job?.isActive == true) {
            logW("Client id=$id already has active subscription")
            job
        } else {
            subscribe(lifecycleOwner.lifecycleScope, collector).also {
                lifecycleOwnerSubscriptions[id] = it
                logI("Client id=${id} active subscription has been saved")
            }
        }
    }

    /**
     * Unsubscribes a lifecycle owner from listening to the socket's flow. In common cases yon
     * won't need to call this method manually. Because coroutines lifecycle does it for you.
     */
    fun unsubscribe(lifecycleOwner: LifecycleOwner) {
        val id = lifecycleOwner.hashCode()
        lifecycleOwnerSubscriptions[id]?.cancel()
            ?: run {
                logW("There is no active subscription of client id=$id")
            }
    }

    /**
     * Pauses and closes the socket with shutdown code. For reopening - use [resume]
     */
    fun close() {
        if (socketState == STATE_CLOSED) {
            logI("Socket is closed")
        } else {
            pause()
            close(CODE_SHUTDOWN)
        }
    }

    /**
     * Pauses the socket
     */
    fun pause() {
        stopConnecting()
        if (socketState == STATE_CLOSED) {
            logI("Socket is closed")
        } else {
            socketState = STATE_PAUSED
            logI("Socket is paused")
        }
    }

    /**
     * Resumes the socket if there are some subscribers else starts connecting
     */
    fun resume() {
        if (socketState in arrayOf(STATE_OPEN, STATE_CONNECTING)) {
            logI("Socket is resumed")
            return
        }
        if (socket == null) {
            if (clientsCounter.get() == 0) {
                logW("Unable to resume socket if there are not client's subscriptions")
            } else {
                logI("Resuming socket...")
                socketState = STATE_CLOSED
                startConnecting()
            }
        } else {
            socketState = STATE_OPEN
            logI("Socket is resumed")
        }
    }

    /**
     * Sends data to the socket if it's in [STATE_OPEN]
     * @return true on success
     */
    fun send(data: String): Boolean {
        return if (socketState != STATE_OPEN) {
            logW("Unable to send data to socket in $socketState")
            false
        } else socket?.send(data) ?: false
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
                        logI("Start connecting to ${settings.socketUrl}...")
                        val job = timerFlow(
                            settings.connectTimeoutMills, settings.connectInitDelayMills
                        ).onEach {
                            if (socketState != STATE_OPEN) {
                                onConnecting()
                            } else {
                                logI("Connection is already open")
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
        if (socketState != STATE_OPEN) return
        coroutineScope.launch {
            responseDeserializer.fromString(string)?.let {
                socketFlow.emit(it) // send to clients
            }
        }
    }

    private fun onMessage(byteString: ByteString) {
        if (socketState != STATE_OPEN) return
        coroutineScope.launch {
            responseDeserializer.fromByteString(byteString)?.let {
                socketFlow.emit(it) // send to clients
            }
        }
    }

    private fun startSilenceTimer() {
        if (settings.silenceTimeoutMills <= 0L) return
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
        socketState = STATE_OPEN
        stopConnecting()
        startSilenceTimer()
        logI("Socket connection to ${settings.socketUrl} is open")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        logW("onClosed: code = %d, reason = %s", code, reason)
        socketState = STATE_CLOSED
        logI("Socket connection to ${settings.socketUrl} is closed")
        socket = null
        stopSilenceTimer()
        if (SocketCloseCode.fromInt(code) == CODE_SILENCE_TIMER) startConnecting()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        logE("onFailure: error = %s", t.toString())
        onFailure(t)
        socket = null
        stopSilenceTimer()
        if (socketState !in arrayOf(STATE_CONNECTING, STATE_PAUSED)) {
            socketState = STATE_CLOSED
            startConnecting()
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        logV("onMessage: byte string size = %d", bytes.size)
        onMessage(bytes)
        startSilenceTimer()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        logV("onMessage: %s", text)
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

    private fun removeInactiveSubscriptions() {
        lifecycleOwnerSubscriptions.entries.forEach {
            if (!it.value.isActive) {
                lifecycleOwnerSubscriptions.remove(it.key)
                logI("Client id=${it.key} inactive subscription has been removed")
            }
        }
    }

    private fun startClientsCounting() {
        val job = socketFlow
            .subscriptionCount
            .onEach { newCount ->
                val oldCount = clientsCounter.getAndSet(newCount)
                if (newCount > oldCount)
                    logI("Client subscribed (clients count=%d)", newCount)
                else if (newCount < oldCount) {
                    logI("Client unsubscribed (clients count=%d)", newCount)
                    removeInactiveSubscriptions()
                }
                // if there are no clients - close the connection, else resume
                if (newCount == 0)
                    close(CODE_NO_CLIENTS)
                else
                    resume()
            }
            .launchIn(coroutineScope)
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
    private fun logV(message: String, vararg args: Any?) = log(Log.VERBOSE, message, *args)
    private fun logI(message: String, vararg args: Any?) = log(Log.INFO, message, *args)

    @Suppress("SameParameterValue")
    private fun logW(message: String, vararg args: Any?) = log(Log.WARN, message, *args)

    @Suppress("SameParameterValue")
    private fun logE(message: String, vararg args: Any?) = log(Log.ERROR, message, *args)

    private fun formatLog(message: String?, vararg args: Any?) =
        String.format("WebSocket(thread=${Thread.currentThread().id}) $message", *args)

    /**
     * Socket's states
     */
    enum class SocketState {
        STATE_CONNECTING, // socket is connecting
        STATE_OPEN,       // socket is open
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

    /**
     * WebSocket's settings
     * @param socketUrl socket server url
     * @param connectTimeoutMills timeout between socket connection attempts
     * @param connectInitDelayMills starting timeout before socket connection attempt
     * @param silenceTimeoutMills if open socket wouldn't receive any message in this timeout -
     * it'll be reopen. If value = 0 - not using
     * @param readTimeoutMills default read timeout for new connections
     * @param pingTimeoutMills interval between HTTP/2 and web socket pings initiated by this client
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
        private const val CONNECT_TIMEOUT_MILLS = 5_000L
        private const val CONNECT_INIT_DELAY_MILLS = 1_000L
        private const val SILENCE_TIMEOUT_MILLS = 60_000L
        private const val READ_TIMEOUT_MILLS = 30_000L
        private const val PING_TIMEOUT_MILLS = 10_000L

        private val defCoroutineScope: CoroutineScope
            get() = ProcessLifecycleOwner.get().lifecycleScope + Dispatchers.IO
    }
}