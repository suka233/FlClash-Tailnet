package com.follow.clash.tailnet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.io.File
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors

class TailnetService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var broker: ProtectionBroker? = null
    private var daemon: Process? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var dns: TailnetDns? = null
    private val lifecycleLock = Any()
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!TailnetRuntime.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        showNotification()
        executor.execute {
            synchronized(lifecycleLock) {
                if (!stopped && !TailnetRuntime.alive(daemon) && TailnetRuntime.enabled(this)) {
                    cleanup()
                    runCatching { startDaemon() }.onFailure {
                        runCatching { cleanup() }
                        TailnetRuntime.error = it.message
                        TailnetRuntime.process = null
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tailnet", getString(R.string.tailnet_service_channel), NotificationManager.IMPORTANCE_LOW))
        val launch = packageManager.getLaunchIntentForPackage(packageName)!!
        val pending = PendingIntent.getActivity(this, 41, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(41, NotificationCompat.Builder(this, "tailnet")
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(R.string.tailnet_service_title))
            .setContentText(getString(R.string.tailnet_service_active))
            .setContentIntent(pending).setOngoing(true).build())
    }

    private fun startDaemon() {
        val root = TailnetRuntime.root(this)
        val state = File(root, "state").apply { mkdirs() }
        File(root, "daemon.pid").delete()
        val name = "$packageName.tailnet.${UUID.randomUUID()}"
        broker = ProtectionBroker(this, name)
        updateNetwork()
        val command = ProcessBuilder(
            File(applicationInfo.nativeLibraryDir, "libtailnet.so").path,
            "--tun=userspace-networking", "--statedir=${state.path}",
            "--socket=${File(root, "tailscaled.sock").path}",
            "--socks5-server=127.0.0.1:${TailnetRuntime.port(this)}",
        ).directory(root).redirectErrorStream(true).redirectOutput(File(root, "daemon.log"))
        command.environment().putAll(mapOf(
            "TS_NO_LOGS_NO_SUPPORT" to "true", "TS_AUTH_ONCE" to "true",
            "TS_LOGS_DIR" to File(root, "logs").path,
            "FLCLASH_PROTECT_SOCKET" to "@$name",
            "FLCLASH_PID_FILE" to File(root, "daemon.pid").path,
            "FLCLASH_NETWORK_STATE" to File(root, "network.json").path,
            "FLCLASH_OS_VERSION" to Build.VERSION.RELEASE,
            "FLCLASH_DEVICE_MODEL" to Build.MODEL,
            "FLCLASH_PACKAGE" to packageName,
            "FLCLASH_SOCKS_USER" to "flclash",
            "FLCLASH_SOCKS_PASSWORD" to TailnetRuntime.password(this),
        ))
        daemon = command.start()
        TailnetRuntime.process = daemon
        TailnetRuntime.error = null
        dns = TailnetDns(this).also { TailnetRuntime.dnsPort = it.port }
        val connectivity = getSystemService(ConnectivityManager::class.java)
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkChanged()
            override fun onLost(network: Network) = networkChanged()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkChanged()
            override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) = networkChanged()
        }.also {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), it)
        }
    }

    private fun networkChanged() {
        if (stopped) return
        runCatching { executor.execute { if (!stopped) {
            runCatching { updateNetwork(); signalDaemon() }.onFailure { TailnetRuntime.error = it.message }
        } } }
    }

    private fun signalDaemon() {
        val process = daemon ?: return
        if (TailnetRuntime.alive(process)) runCatching {
            val pid = File(TailnetRuntime.root(this), "daemon.pid").readText().trim().toInt()
            Os.kill(pid, OsConstants.SIGUSR1)
        }
    }

    private fun updateNetwork() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val physical = physicalNetwork(connectivity)
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.name.startsWith("tun") }.map {
            mapOf("name" to it.name, "index" to it.index, "mtu" to it.mtu,
                "addresses" to it.interfaceAddresses.mapNotNull { address -> address.address?.hostAddress?.substringBefore('%')?.let { ip -> "$ip/${address.networkPrefixLength}" } })
        }
        val data = mapOf("default" to (physical?.let { connectivity.getLinkProperties(it)?.interfaceName } ?: ""), "interfaces" to interfaces)
        val root = TailnetRuntime.root(this)
        val temp = File(root, "network.tmp")
        temp.writeText(Gson().toJson(data))
        check(temp.renameTo(File(root, "network.json")))
    }

    override fun onDestroy() {
        stopped = true
        synchronized(lifecycleLock) {
            cleanup()
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun cleanup() {
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
        val old = daemon
        old?.destroy()
        if (old != null && !old.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            old.destroyForcibly()
            check(old.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { "Tailscale process did not exit" }
        }
        daemon = null
        TailnetRuntime.process = null
        dns?.close(); dns = null; TailnetRuntime.dnsPort = 0
        broker?.close(); broker = null
    }
}
