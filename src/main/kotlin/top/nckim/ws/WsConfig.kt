package top.nckim.ws

data class WsConfig(
    val source: String,
    val playerUuid: String,
    val playerIgn: String,
    val island: String,
    val heartbeatIntervalMs: Long = 30_000L,
    val maxReconnectAttempts: Int = 10,
    val reconnectBaseDelayMs: Long = 2_000L,
    val reconnectBackoffFactor: Double = 2.0
)
