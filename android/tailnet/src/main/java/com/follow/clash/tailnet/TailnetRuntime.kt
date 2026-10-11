package com.follow.clash.tailnet

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.io.File
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.UUID

object TailnetRuntime {
    private val gson = Gson()
    @Volatile var process: Process? = null
    @Volatile var error: String? = null
    @Volatile var dnsPort: Int = 0

    fun alive(process: Process?) = process != null && runCatching { process.exitValue(); false }.getOrDefault(true)

    fun root(context: Context) = File(context.filesDir, "tailnet").apply { mkdirs() }
    fun api(context: Context) = LocalApi(File(root(context), "tailscaled.sock").path)
    fun prefs(context: Context) = context.getSharedPreferences("tailnet", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).commit()
        if (enabled) ContextCompat.startForegroundService(context, Intent(context, TailnetService::class.java))
        else context.stopService(Intent(context, TailnetService::class.java))
    }

    @Synchronized fun port(context: Context): Int {
        val settings = prefs(context)
        val existing = settings.getInt("socksPort", 0)
        if (existing != 0) return existing
        val selected = ServerSocket(0).use { it.localPort }
        settings.edit().putInt("socksPort", selected).commit()
        return selected
    }

    @Synchronized fun password(context: Context): String {
        val settings = prefs(context)
        return settings.getString("password", null) ?: UUID.randomUUID().toString().also {
            settings.edit().putString("password", it).commit()
        }
    }

    fun snapshot(context: Context): Map<String, Any?> {
        val alive = alive(process)
        val result = mutableMapOf<String, Any?>(
            "enabled" to enabled(context), "running" to alive, "error" to error,
            "socksPort" to port(context), "username" to "flclash", "password" to password(context),
            "dnsPort" to prefs(context).getInt("dnsPort", dnsPort),
            "controlUrl" to prefs(context).getString("controlUrl", "https://controlplane.tailscale.com"),
            "routing" to prefs(context).getString("routing", null)?.let { gson.fromJson(it, Map::class.java) },
        )
        if (alive) {
            result["status"] = runCatching { json(context, "status") }.getOrElse { result["error"] = it.message; null }
            val status = result["status"] as? Map<*, *>
            if (status?.get("BackendState") == "Running") {
                val routing = routingSnapshot(status)
                val encoded = gson.toJson(routing)
                if (prefs(context).getString("routing", null) != encoded) prefs(context).edit().putString("routing", encoded).apply()
                result["routing"] = routing
            }
            result["files"] = runCatching { json(context, "files/") }.getOrDefault(emptyList<Any>())
            result["targets"] = runCatching { json(context, "file-targets") }.getOrDefault(emptyList<Any>())
        }
        return result
    }

    fun json(context: Context, endpoint: String): Any? = gson.fromJson(
        api(context).request("GET", "/localapi/v0/$endpoint").toString(Charsets.UTF_8), Any::class.java,
    )

    fun login(context: Context, controlUrl: String): String {
        require(controlUrl.startsWith("https://") || controlUrl.startsWith("http://"))
        if (prefs(context).getString("controlUrl", null) != controlUrl) prefs(context).edit().remove("routing").commit()
        val options = mapOf("UpdatePrefs" to mapOf("ControlURL" to controlUrl, "WantRunning" to true, "RouteAll" to true, "CorpDNS" to true, "Hostname" to "flclash-${android.os.Build.MODEL.replace(' ', '-')}", "ShieldsUp" to false))
        api(context).request("POST", "/localapi/v0/start", gson.toJson(options).toByteArray())
        api(context).request("POST", "/localapi/v0/login-interactive")
        prefs(context).edit().putString("controlUrl", controlUrl).apply()
        return ""
    }

    fun send(context: Context, peerId: String, file: File, name: String) {
        require(peerId.matches(Regex("[A-Za-z0-9_-]+")))
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\'))
        file.inputStream().use { input ->
            api(context).exchange("PUT", "/localapi/v0/file-put/$peerId/${escape(name)}", input, file.length(), java.io.ByteArrayOutputStream())
        }
    }

    fun receive(context: Context, name: String, output: java.io.OutputStream) {
        require(name.isNotEmpty() && !name.contains('/') && !name.contains('\\'))
        api(context).exchange("GET", "/localapi/v0/files/${escape(name)}", null, 0, output)
    }

    private fun escape(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun routingSnapshot(status: Map<*, *>): Map<String, Any?> {
        fun peer(value: Any?): Map<String, Any?> {
            val fields = value as? Map<*, *> ?: emptyMap<Any, Any>()
            return mapOf("TailscaleIPs" to fields["TailscaleIPs"], "PrimaryRoutes" to fields["PrimaryRoutes"], "DNSName" to fields["DNSName"])
        }
        val peers = (status["Peer"] as? Map<*, *>)?.map { (key, value) -> key.toString() to peer(value) }?.toMap()
        return mapOf("MagicDNSSuffix" to status["MagicDNSSuffix"], "Self" to peer(status["Self"]), "Peer" to peers)
    }
}
