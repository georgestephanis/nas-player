package dev.atv.nasplayer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.atv.nasplayer.source.UsbDrives
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** What to store for [password]: a blank one keeps [unread], the stored value that couldn't be decrypted. */
internal fun storedPassword(password: String, unread: String?, encrypt: (String) -> String): String =
    if (password.isEmpty() && unread != null) unread else encrypt(password)

object ServerRepository {
    private const val KEY = "servers"
    private lateinit var prefs: SharedPreferences
    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    /** Disk + Keystore work runs here, never on the main thread (keystore calls can block for seconds). */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val writeLock = Mutex()
    /** Stored passwords that couldn't be decrypted this run, by server id, so a write can't erase them. Only touched on [io]. */
    private val unreadPasswords = mutableMapOf<String, String>()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
        io.launch {
            _servers.value = load()
            _loaded.value = true
        }
    }

    fun get(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id } ?: UsbDrives.get(id)

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

    /** Updates the in-memory list immediately; encrypts and writes in the background. */
    private fun persist(list: List<ServerConfig>) {
        _servers.value = list
        io.launch { writeLock.withLock { write(list) } }
    }

    private fun write(list: List<ServerConfig>) {
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
                put("password", storedPassword(s.password, unreadPasswords[s.id], SecretBox::encrypt))
                put("domain", s.domain)
                put("allowInsecureTls", s.allowInsecureTls)
            })
            if (s.password.isNotEmpty()) unreadPasswords.remove(s.id)
        }
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    private fun load(): List<ServerConfig> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val stored = o.optString("password")
                ServerConfig(
                    id = id,
                    name = o.getString("name"),
                    type = ServerType.valueOf(o.getString("type")),
                    host = o.getString("host"),
                    port = if (o.has("port")) o.getInt("port") else null,
                    path = o.optString("path"),
                    username = o.optString("username"),
                    password = SecretBox.decrypt(stored) ?: run { unreadPasswords[id] = stored; "" },
                    domain = o.optString("domain"),
                    allowInsecureTls = o.optBoolean("allowInsecureTls"),
                )
            }
        } catch (e: Exception) {
            // The next save replaces the list, so keep a copy. It can only be recovered over adb; add a restore if this ever happens.
            Log.e("ServerRepository", "Saved sources are unreadable", e)
            prefs.edit().putString("$KEY.unreadable", raw).commit()
            emptyList()
        }
    }
}
