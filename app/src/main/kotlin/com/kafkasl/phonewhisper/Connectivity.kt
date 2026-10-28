package com.kafkasl.phonewhisper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Connectivity {
    /**
     * True when there is an active network that claims internet access. Deliberately does not
     * require Android's "validated" flag, so a LAN-only self-hosted server is still tried; an
     * unreachable server is caught by the client's short connect timeout instead.
     */
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
