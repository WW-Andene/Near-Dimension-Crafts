package com.arhand.feature.stream

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GAP-11 — LAN discovery for OSC receivers using Android NsdManager (mDNS).
 *
 * VSeeFace, VTube Studio, Warudo, and other VTubing/mocap apps announce themselves
 * on the local network via mDNS service records. This class discovers those receivers
 * and presents them as a selectable list so users don't need to type an IP address.
 *
 * ## Service types probed
 *
 * | App            | Service type         |
 * |----------------|----------------------|
 * | VSeeFace       | `_vseeface._udp`     |
 * | VTube Studio   | `_vtube-studio._tcp` |
 * | Warudo         | `_osc._udp`          |
 * | Generic OSC    | `_osc._udp`          |
 *
 * ## Usage
 *
 * ```kotlin
 * val discovery = OscDiscovery(context)
 * discovery.start()
 * discovery.found.collect { receivers -> /* show list */ }
 * // user selects one:
 * discovery.stop()
 * vm.applyDiscoveredReceiver(receiver)
 * ```
 */
class OscDiscovery(private val context: Context) {

    data class DiscoveredReceiver(
        val name:    String,
        val host:    String,
        val port:    Int,
        val service: String   // human label: "VSeeFace", "VTube Studio", "OSC"
    )

    private val _found  = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val found: StateFlow<List<DiscoveredReceiver>> = _found

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var nsdManager: NsdManager? = null
    private val listeners  = mutableListOf<NsdManager.DiscoveryListener>()
    private val discovered = mutableMapOf<String, DiscoveredReceiver>()

    private val serviceTypes = listOf(
        "_osc._udp."        to "OSC",
        "_vseeface._udp."   to "VSeeFace",
        "_vtube-studio._tcp." to "VTube Studio"
    )

    fun start() {
        if (_scanning.value) return
        val nm = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = nm
        discovered.clear()
        _found.value  = emptyList()
        _scanning.value = true

        for ((type, label) in serviceTypes) {
            val listener = makeListener(nm, type, label)
            listeners.add(listener)
            try {
                nm.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                android.util.Log.w("OscDiscovery", "Cannot discover $type: ${e.message}")
            }
        }
    }

    fun stop() {
        val nm = nsdManager ?: return
        listeners.forEach { l ->
            try { nm.stopServiceDiscovery(l) } catch (_: Exception) {}
        }
        listeners.clear()
        nsdManager = null
        _scanning.value = false
    }

    private fun makeListener(
        nm:    NsdManager,
        type:  String,
        label: String
    ): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(s: String, e: Int)  {}
        override fun onStopDiscoveryFailed(s: String, e: Int)   {}
        override fun onDiscoveryStarted(s: String)              {}
        override fun onDiscoveryStopped(s: String)              {}
        override fun onServiceLost(info: NsdServiceInfo)        {
            discovered.remove(info.serviceName)
            _found.value = discovered.values.toList()
        }
        override fun onServiceFound(info: NsdServiceInfo) {
            nm.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress ?: return
                    val port = i.port
                    val rec  = DiscoveredReceiver(
                        name    = i.serviceName,
                        host    = host,
                        port    = port,
                        service = label
                    )
                    discovered[i.serviceName] = rec
                    _found.value = discovered.values.toList()
                }
            })
        }
    }
}
