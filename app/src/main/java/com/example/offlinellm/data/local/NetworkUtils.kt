package com.example.offlinellm.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** Best-effort LAN IPv4 addresses (Wi‑Fi / Ethernet / hotspot), no loopback. */
    fun getLocalIpv4Addresses(context: Context): List<String> {
        val found = linkedSetOf<String>()

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nets = cm.allNetworks
            for (n in nets) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) continue
                val lp: LinkProperties = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        found.add(a.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Throwable) {
        }

        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val s = String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
                if (s != "0.0.0.0") found.add(s)
            }
        } catch (_: Throwable) {
        }

        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        found.add(a.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return found.toList()
    }

    fun primaryIpv4(context: Context): String =
        getLocalIpv4Addresses(context).firstOrNull() ?: "127.0.0.1"

    fun baseUrl(ip: String, port: Int): String = "http://$ip:$port"

    fun openaiBase(ip: String, port: Int): String = "http://$ip:$port/v1"
}
