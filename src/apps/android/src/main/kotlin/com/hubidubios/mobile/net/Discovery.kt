package com.hubidubios.mobile.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * LAN discovery of the workstation via NsdManager (_hubidubios._tcp). Discovery is
 * best-effort: manual host entry is always offered as a fallback, since mDNS
 * fails on guest networks, some mesh routers, and across VLANs.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null

    data class Found(val host: String, val port: Int, val fingerprint: String?)

    fun start(onFound: (Found) -> Unit) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val fp = si.attributes["fp"]?.toString(Charsets.UTF_8)
                        onFound(Found(si.host.hostAddress ?: return, si.port, fp))
                    }
                })
            }
        }
        listener = l
        nsd.discoverServices("_hubidubios._tcp", NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }
}
