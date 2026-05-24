package top.nckim.ws

import com.google.gson.JsonObject

interface WsListener {
    fun onConnected(onlineCount: Int) {}
    fun onDisconnected() {}
    fun onPlayerJoin(ign: String) {}
    fun onPlayerLeave(ign: String) {}
    fun onChatReceived(ign: String, content: String) {}
    fun onEventReceived(ign: String, eventType: String, data: JsonObject) {}
    fun onErrorReceived(code: String, message: String) {}
    fun onPong() {}
    fun onConnectionStateChanged(state: WsConnectionState) {}
}
