package com.follow.clash.tailnet

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.Executors

/** A loopback-only DNS adapter; other names keep the profile's original resolver. */
class TailnetDns(context: Context) : Closeable {
    private val socket = DatagramSocket(TailnetRuntime.prefs(context).getInt("dnsPort", 0), InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(3)
    private val api = LocalApi(java.io.File(TailnetRuntime.root(context), "tailscaled.sock").path, 5000)
    val port: Int = socket.localPort

    init {
        TailnetRuntime.prefs(context).edit().putInt("dnsPort", port).commit()
        workers.execute {
            while (!socket.isClosed) {
                val packet = DatagramPacket(ByteArray(4096), 4096)
                if (runCatching { socket.receive(packet) }.isFailure) break
                val query = packet.data.copyOf(packet.length)
                workers.execute {
                    val response = runCatching {
                        val (name, type) = DnsQuestion.decode(query)
                        val endpoint = "/localapi/v0/dns-query?name=${URLEncoder.encode(name, "UTF-8")}&type=$type"
                        val result = Gson().fromJson(api.request("GET", endpoint).toString(Charsets.UTF_8), Map::class.java)
                        Base64.decode(result["Bytes"] as String, Base64.DEFAULT).also {
                            require(it.size >= 12); it[0] = query[0]; it[1] = query[1]
                        }
                    }.getOrElse { DnsQuestion.failure(query) }
                    runCatching { socket.send(DatagramPacket(response, response.size, packet.socketAddress)) }
                }
            }
        }
    }

    override fun close() { socket.close(); workers.shutdownNow() }
}

internal object DnsQuestion {
    private val types = mapOf(1 to "A", 28 to "AAAA", 5 to "CNAME", 12 to "PTR", 15 to "MX", 16 to "TXT", 33 to "SRV", 6 to "SOA", 2 to "NS")
    fun decode(packet: ByteArray): Pair<String, String> {
        require(packet.size >= 17 && packet[4] == 0.toByte() && packet[5] == 1.toByte())
        var offset = 12
        val labels = mutableListOf<String>()
        while (true) {
            require(offset < packet.size)
            val length = packet[offset++].toInt() and 255
            if (length == 0) break
            require(length <= 63 && offset + length <= packet.size && labels.size < 128)
            labels += packet.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
            offset += length
        }
        require(offset + 4 <= packet.size)
        val type = ((packet[offset].toInt() and 255) shl 8) or (packet[offset + 1].toInt() and 255)
        require(packet[offset + 2] == 0.toByte() && packet[offset + 3] == 1.toByte())
        return labels.joinToString(".") + "." to (types[type] ?: error("Unsupported DNS type"))
    }
    fun failure(query: ByteArray): ByteArray = ByteArray(12).also {
        if (query.size >= 2) { it[0] = query[0]; it[1] = query[1] }
        it[2] = 0x81.toByte(); it[3] = 0x82.toByte()
    }
}
