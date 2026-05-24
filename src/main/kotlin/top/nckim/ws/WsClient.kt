package top.nckim.ws

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import top.nckim.utils.Base64Utils
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

class WsClient(
    val config: WsConfig,
    initialListeners: List<WsListener> = emptyList(),
    private val serverUrl: String = SERVER_URL,
    private val customHostnameVerifier: HostnameVerifier? = null
) {
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList(initialListeners)
    private val connectionState = AtomicReference(WsConnectionState.DISCONNECTED)
    private val intentionalDisconnect = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(config.heartbeatIntervalMs, TimeUnit.MILLISECONDS)
        .apply { if (customHostnameVerifier != null) hostnameVerifier(customHostnameVerifier) }
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var reconnectJob: Job? = null

    companion object {
        const val SERVER_URL = "wss://nckim.top/ws"
        const val TYPE_CONNECT = "connect"
        const val TYPE_SYSTEM = "system"
        const val TYPE_PLAYER_JOIN = "player_join"
        const val TYPE_PLAYER_LEAVE = "player_leave"
        const val TYPE_DISCONNECT = "disconnect"
        const val TYPE_CHAT = "chat"
        const val TYPE_EVENT = "event"
        const val TYPE_PING = "ping"
        const val TYPE_PONG = "pong"
        const val TYPE_ERROR = "error"
    }

    private data class MessageEnvelope(
        val type: String = "",
        val source: String? = null,
        val from: String? = null,
        val timestamp: Long? = null,
        val payload: JsonObject? = null,
        val content: String? = null
    )

    fun addListener(listener: WsListener) = listeners.add(listener)
    fun removeListener(listener: WsListener) = listeners.remove(listener)

    fun getConnectionState(): WsConnectionState = connectionState.get()

    fun connect() {
        intentionalDisconnect.set(false)
        reconnectAttempt.set(0)
        connectionState.set(WsConnectionState.CONNECTING)
        fireEvent { onConnectionStateChanged(WsConnectionState.CONNECTING) }

        val request = Request.Builder().url(serverUrl).build()
        webSocket = okHttpClient.newWebSocket(request, wsListener())
    }

    fun disconnect() {
        intentionalDisconnect.set(true)
        sendEnvelope(TYPE_DISCONNECT)
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    fun shutdown() {
        intentionalDisconnect.set(true)
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client shutdown")
        webSocket = null
        scope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    fun sendChat(content: String) {
        val obfuscated = Base64Utils.encodeWithOffset(content)
        val payload = JsonObject().apply { addProperty("content", obfuscated) }
        sendEnvelope(TYPE_CHAT, payload)
    }

    fun sendEvent(eventType: String, data: JsonObject) {
        val payload = JsonObject().apply {
            addProperty("eventType", eventType)
            add("data", data)
        }
        sendEnvelope(TYPE_EVENT, payload)
    }

    fun sendPingMessage() {
        sendEnvelope(TYPE_PING)
    }

    private fun wsListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt.set(0)
            connectionState.set(WsConnectionState.CONNECTED)
            fireEvent { onConnectionStateChanged(WsConnectionState.CONNECTED) }

            val payload = JsonObject().apply {
                addProperty("ign", config.playerIgn)
                addProperty("island", config.island)
            }
            sendEnvelope(TYPE_CONNECT, payload)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = parseEnvelope(text) ?: return
            handleMessage(envelope)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@WsClient.webSocket = null
            connectionState.set(WsConnectionState.DISCONNECTED)
            fireEvent { onDisconnected() }
            fireEvent { onConnectionStateChanged(WsConnectionState.DISCONNECTED) }

            if (!intentionalDisconnect.get()) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@WsClient.webSocket = null
            if (!intentionalDisconnect.get()) {
                fireEvent { onErrorReceived("CONNECTION_ERROR", t.message ?: "Unknown error") }
            }
        }
    }

    private fun handleMessage(envelope: MessageEnvelope) {
        when (envelope.type) {
            TYPE_SYSTEM -> handleSystem(envelope)
            TYPE_PLAYER_JOIN -> handlePlayerJoin(envelope.payload)
            TYPE_PLAYER_LEAVE -> handlePlayerLeave(envelope.payload)
            TYPE_CHAT -> handleChat(envelope)
            TYPE_EVENT -> handleEvent(envelope)
            TYPE_ERROR -> handleError(envelope.payload)
            TYPE_PONG -> fireEvent { onPong() }
        }
    }

    private fun handleSystem(envelope: MessageEnvelope) {
        val content = envelope.payload?.get("content")?.asString ?: envelope.content ?: return
        if (content == "connected") {
            val onlineCount = envelope.payload?.get("onlineCount")?.asInt ?: 0
            fireEvent { onConnected(onlineCount) }
        }
    }

    private fun handlePlayerJoin(payload: JsonObject?) {
        val ign = payload?.get("ign")?.asString ?: return
        fireEvent { onPlayerJoin(ign) }
    }

    private fun handlePlayerLeave(payload: JsonObject?) {
        val ign = payload?.get("ign")?.asString ?: return
        fireEvent { onPlayerLeave(ign) }
    }

    private fun handleChat(envelope: MessageEnvelope) {
        val payload = envelope.payload ?: return
        val ign = payload.get("ign")?.asString ?: envelope.from ?: return
        val rawContent = payload.get("content")?.asString ?: return
        val content = Base64Utils.decodeWithOffset(rawContent) ?: rawContent
        fireEvent { onChatReceived(ign, content) }
    }

    private fun handleEvent(envelope: MessageEnvelope) {
        val payload = envelope.payload ?: return
        val ign = payload.get("ign")?.asString ?: envelope.from ?: return
        val eventType = payload.get("eventType")?.asString ?: return
        val data = payload.get("data")?.asJsonObject ?: JsonObject()
        fireEvent { onEventReceived(ign, eventType, data) }
    }

    private fun handleError(payload: JsonObject?) {
        val code = payload?.get("code")?.asString ?: "UNKNOWN"
        val message = payload?.get("message")?.asString ?: "No error message"
        fireEvent { onErrorReceived(code, message) }
    }

    private fun sendEnvelope(type: String, payload: JsonObject? = null) {
        val ws = webSocket ?: return
        val obj = JsonObject().apply {
            addProperty("type", type)
            addProperty("source", config.source)
            addProperty("from", config.playerUuid)
            addProperty("timestamp", System.currentTimeMillis())
            if (payload != null) add("payload", payload)
        }
        ws.send(gson.toJson(obj))
    }

    private fun parseEnvelope(json: String): MessageEnvelope? {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            MessageEnvelope(
                type = obj.get("type")?.asString ?: return null,
                source = obj.get("source")?.asString,
                from = obj.get("from")?.asString,
                timestamp = obj.get("timestamp")?.asLong,
                payload = obj.get("payload")?.asJsonObject,
                content = obj.get("content")?.asString
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob != null) return

        val attempt = reconnectAttempt.getAndIncrement()
        if (attempt >= config.maxReconnectAttempts) return

        val delayMs = (config.reconnectBaseDelayMs * config.reconnectBackoffFactor.pow(attempt.toDouble())).toLong()

        connectionState.set(WsConnectionState.RECONNECTING)
        fireEvent { onConnectionStateChanged(WsConnectionState.RECONNECTING) }

        reconnectJob = scope.launch {
            try {
                delay(delayMs)
                if (intentionalDisconnect.get()) return@launch

                connectionState.set(WsConnectionState.CONNECTING)
                fireEvent { onConnectionStateChanged(WsConnectionState.CONNECTING) }

                val request = Request.Builder().url(serverUrl).build()
                webSocket = okHttpClient.newWebSocket(request, wsListener())
            } finally {
                reconnectJob = null
            }
        }
    }

    private fun fireEvent(event: WsListener.() -> Unit) {
        for (listener in listeners) {
            try {
                listener.event()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
