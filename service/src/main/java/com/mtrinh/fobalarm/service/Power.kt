package com.mtrinh.fobalarm.service

import android.content.Context
import android.os.PowerManager

/**
 * The two facts a peer reports about itself that are not already in DeviceView, both read where a
 * Context exists so :data stays Android-light.
 */
object Power {

    /**
     * Battery saver. Reported because it throttles background work at ANY charge level once it is
     * switched on by hand -- the under-20 % check would not see that. The alarm phone refuses to
     * block a ring on the word of a sentinel in this state.
     */
    fun saveMode(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(PowerManager::class.java).isPowerSaveMode }
            .getOrDefault(false)

    /**
     * This phone's address on the ordinary network, if it has one.
     *
     * The second presence channel. The things that make Wi-Fi Direct membership flap -- Android's
     * 10 s client-group idle timeout, the 45 s rejoin guard, the alarm phone tearing its own group
     * down -- do not touch home Wi-Fi. So a sentinel that is home but momentarily unjoined is still
     * reachable here, and does not read as having left.
     *
     * The P2P interface is excluded: 192.168.49.x is the group's own subnet and proves nothing
     * about being on the home network.
     */
    fun lanIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { it.isNotBlank() && !it.startsWith("192.168.49.") }
    }.getOrNull()
}
