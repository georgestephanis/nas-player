package dev.atv.nasplayer.source

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Xml
import dev.atv.nasplayer.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebDavBrowser(override val server: ServerConfig) : MediaBrowser {

    override val rootPath: String = "/"

    private val base: HttpUrl = baseUrl(server)

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val url = resolve(server, path)
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/>
            </d:prop></d:propfind>""".trimIndent()
        val request = Request.Builder()
            .url(url)
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        client(server).newCall(request).execute().use { resp ->
            if (resp.code == 401) throw IOException("Authentication failed (HTTP 401)")
            if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
            val xml = resp.body.string()
            val selfPath = decodedPath(url)
            sortEntries(
                parse(xml).filter { it.hrefPath.trimEnd('/') != selfPath.trimEnd('/') }
                    .mapNotNull { r ->
                        val rel = toRelative(r.hrefPath) ?: return@mapNotNull null
                        val name = r.displayName?.takeIf { it.isNotBlank() }
                            ?: rel.trimEnd('/').substringAfterLast('/')
                        if (name.startsWith(".")) return@mapNotNull null
                        RemoteEntry(name, rel.trimEnd('/').ifEmpty { "/" }, r.isCollection, r.length, r.modifiedMs)
                    }
            )
        }
    }

    override fun playbackUri(path: String): Uri =
        Uri.Builder().scheme("dav").authority(server.id).path(path).build()

    private fun toRelative(hrefPath: String): String? {
        val basePath = decodedPath(base).trimEnd('/')
        if (!hrefPath.startsWith(basePath)) return null
        val rel = hrefPath.removePrefix(basePath)
        return if (rel.startsWith("/")) rel else "/$rel"
    }

    private class DavResponse(
        val hrefPath: String,
        val displayName: String?,
        val isCollection: Boolean,
        val length: Long,
        val modifiedMs: Long,
    )

    private fun parse(xml: String): List<DavResponse> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(xml.reader())
        }
        val out = mutableListOf<DavResponse>()
        var href: String? = null
        var display: String? = null
        var collection = false
        var length = -1L
        var modified = 0L
        var text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> { href = null; display = null; collection = false; length = -1; modified = 0 }
                        "collection" -> collection = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (parser.name) {
                        "href" -> href = t
                        "displayname" -> display = t
                        "getcontentlength" -> length = t.toLongOrNull() ?: -1
                        "getlastmodified" -> modified = parseHttpDate(t)
                        "response" -> href?.let { h ->
                            val p = if (h.startsWith("http")) decodedPath(h.toHttpUrl()) else decode(h)
                            out += DavResponse(p, display, collection, length, modified)
                        }
                    }
                }
            }
        }
        return out
    }

    companion object {
        private val clients = ConcurrentHashMap<String, Pair<ServerConfig, OkHttpClient>>()

        fun baseUrl(server: ServerConfig): HttpUrl {
            var h = server.host.trim()
            if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://$h"
            val url = h.toHttpUrl()
            return if (server.port != null) url.newBuilder().port(server.port).build() else url
        }

        /** Maps a browse path like "/Movies/Alien.mkv" to an absolute URL under the configured base. */
        fun resolve(server: ServerConfig, path: String): HttpUrl {
            val b = baseUrl(server).newBuilder()
            val baseSegs = baseUrl(server).pathSegments.filter { it.isNotEmpty() }
            b.encodedPath("/")
            baseSegs.forEach { b.addPathSegment(it) }
            path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
            if (path.endsWith("/") || path == "/" || path.isEmpty()) b.addPathSegment("")
            return b.build()
        }

        fun client(server: ServerConfig): OkHttpClient {
            clients[server.id]?.let { (cfg, c) -> if (cfg == server) return c }
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .followRedirects(true)
            if (server.username.isNotBlank()) {
                val creds = Credentials.basic(server.username, server.password)
                builder.addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", creds).build())
                }
            }
            if (server.allowInsecureTls) trustAll(builder)
            return builder.build().also { clients[server.id] = server to it }
        }

        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager", "BadHostnameVerifier")
        private fun trustAll(builder: OkHttpClient.Builder) {
            // Opt-in per server, for NAS boxes using self-signed certificates on the LAN.
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
            builder.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }

        private fun decodedPath(url: HttpUrl) = "/" + url.pathSegments.joinToString("/")

        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

        private fun parseHttpDate(s: String): Long = try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
