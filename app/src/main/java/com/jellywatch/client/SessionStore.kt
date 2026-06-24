package com.jellywatch.client

import android.content.Context
import java.util.UUID

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("jellywatch_session", Context.MODE_PRIVATE)

    fun load(): Session? {
        val server = prefs.getString("server", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val userId = prefs.getString("userId", null) ?: return null
        return Session(
            server = server,
            token = token,
            userId = userId,
            userName = prefs.getString("userName", "Jellyfin") ?: "Jellyfin",
            deviceId = deviceId()
        )
    }

    fun save(session: Session) {
        prefs.edit()
            .putString("server", session.server)
            .putString("token", session.token)
            .putString("userId", session.userId)
            .putString("userName", session.userName)
            .putString("deviceId", session.deviceId)
            .apply()
    }

    fun clear() {
        val id = deviceId()
        prefs.edit().clear().putString("deviceId", id).apply()
    }

    fun deviceId(): String {
        prefs.getString("deviceId", null)?.let { return it }
        return UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }
}
