package dev.atv.nasplayer.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.ArrayDeque

data class DiscoveredServer(val name: String, val host: String, val port: Int)

/** Finds SMB servers advertising themselves over mDNS / Bonjour (Synology, QNAP, TrueNAS, macOS, Samba+Avahi). */
object NetworkDiscovery {
    private const val SMB_SERVICE = "_smb._tcp."

    fun discover(context: Context): Flow<List<DiscoveredServer>> = callbackFlow {
        val app = context.applicationContext
        val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("nasplayer-discovery")?.apply { setReferenceCounted(false); acquire() }

        val found = LinkedHashMap<String, DiscoveredServer>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        // NSD callbacks arrive on binder threads; serialize all state access on `found`.
        fun resolveNext(): Unit = synchronized(found) {
            if (resolving) return
            val info = queue.pollFirst() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(found) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val addr = if (Build.VERSION.SDK_INT >= 34) {
                        serviceInfo.hostAddresses.firstOrNull { it is java.net.Inet4Address }
                            ?: serviceInfo.hostAddresses.firstOrNull()
                    } else {
                        @Suppress("DEPRECATION") serviceInfo.host
                    }
                    synchronized(found) {
                        addr?.hostAddress?.let { ip ->
                            found[serviceInfo.serviceName] = DiscoveredServer(serviceInfo.serviceName, ip, serviceInfo.port)
                            trySend(found.values.toList())
                        }
                        resolving = false
                    }
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(found) { queue.add(serviceInfo) }
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(found) {
                    if (found.remove(serviceInfo.serviceName) != null) trySend(found.values.toList())
                }
            }
        }
        trySend(emptyList())
        nsd.discoverServices(SMB_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock?.release()
        }
    }
}
