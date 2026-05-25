package top.nckim.ws

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import top.nckim.utils.Base64Utils
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    @Volatile
    private var webSocket: WebSocketClient? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var heartbeatJob: Job? = null

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

        val client = createWebSocket()
        webSocket = client
        client.connect()
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
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Client shutdown")
        webSocket = null
        scope.cancel()
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

    private fun createWebSocket(): WebSocketClient {
        val uri = URI(serverUrl)
        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake) {
                reconnectAttempt.set(0)
                connectionState.set(WsConnectionState.CONNECTED)
                fireEvent { onConnectionStateChanged(WsConnectionState.CONNECTED) }

                val payload = JsonObject().apply {
                    addProperty("ign", config.playerIgn)
                    addProperty("island", config.island)
                }
                sendEnvelope(TYPE_CONNECT, payload)
                startHeartbeat()
            }

            override fun onMessage(message: String) {
                val envelope = parseEnvelope(message) ?: return
                handleMessage(envelope)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                stopHeartbeat()
                this@WsClient.webSocket = null
                connectionState.set(WsConnectionState.DISCONNECTED)
                fireEvent { onDisconnected() }
                fireEvent { onConnectionStateChanged(WsConnectionState.DISCONNECTED) }

                if (!intentionalDisconnect.get()) {
                    scheduleReconnect()
                }
            }

            override fun onError(ex: Exception) {
                if (!intentionalDisconnect.get()) {
                    fireEvent { onErrorReceived("CONNECTION_ERROR", ex.message ?: "Unknown error") }
                }
            }
        }

        client.setConnectionLostTimeout(0)

        if (customHostnameVerifier != null && serverUrl.startsWith("wss://")) {
            try {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, null, null)
                val delegate = sslContext.socketFactory
                client.setSocketFactory(object : SSLSocketFactory() {
                    override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
                        val socket = delegate.createSocket(s, host, port, autoClose) as SSLSocket
                        socket.setSSLParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
                        return socket
                    }

                    override fun createSocket(host: String?, port: Int): Socket {
                        val socket = delegate.createSocket(host, port) as SSLSocket
                        socket.setSSLParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
                        return socket
                    }

                    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
                        val socket = delegate.createSocket(host, port, localHost, localPort) as SSLSocket
                        socket.setSSLParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
                        return socket
                    }

                    override fun createSocket(host: InetAddress?, port: Int): Socket {
                        val socket = delegate.createSocket(host, port) as SSLSocket
                        socket.setSSLParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
                        return socket
                    }

                    override fun createSocket(host: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
                        val socket = delegate.createSocket(host, port, localAddress, localPort) as SSLSocket
                        socket.setSSLParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
                        return socket
                    }

                    override fun createSocket(): Socket {
                        val socket = delegate.createSocket() as SSLSocket
                        socket.setSSLParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
                        return socket
                    }

                    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
                    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return client
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(config.heartbeatIntervalMs)
                sendPingMessage()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
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
        if (!ws.isOpen) return
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

                val client = createWebSocket()
                webSocket = client
                client.connect()
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
