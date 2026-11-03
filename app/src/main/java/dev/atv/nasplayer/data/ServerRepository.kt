package dev.atv.nasplayer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object ServerRepository {
    private const val KEY = "servers"
    private lateinit var prefs: SharedPreferences
    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
        _servers.value = load()
    }

    fun get(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

    fun save(server: ServerConfig) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        persist(list)
    }

    fun delete(id: String) {
        persist(_servers.value.filterNot { it.id == id })
        ResumeStore.clearServer(id)
    }

    private fun persist(list: List<ServerConfig>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("type", s.type.name)
                put("host", s.host)
                s.port?.let { put("port", it) }
                put("path", s.path)
                put("username", s.username)
                put("password", SecretBox.encrypt(s.password))
                put("domain", s.domain)
                put("allowInsecureTls", s.allowInsecureTls)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
        _servers.value = list
    }

    private fun load(): List<ServerConfig> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ServerConfig(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = ServerType.valueOf(o.getString("type")),
                    host = o.getString("host"),
                    port = if (o.has("port")) o.getInt("port") else null,
                    path = o.optString("path"),
                    username = o.optString("username"),
                    password = SecretBox.decrypt(o.optString("password")),
                    domain = o.optString("domain"),
                    allowInsecureTls = o.optBoolean("allowInsecureTls"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
