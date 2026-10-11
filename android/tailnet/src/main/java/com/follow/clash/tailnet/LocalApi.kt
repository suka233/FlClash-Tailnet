package com.follow.clash.tailnet

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class LocalApi(private val path: String, private val timeoutMillis: Int = 120_000) {
    fun request(method: String, endpoint: String, body: ByteArray = byteArrayOf()): ByteArray {
        val output = ByteArrayOutputStream()
        exchange(method, endpoint, ByteArrayInputStream(body), body.size.toLong(), output)
        return output.toByteArray()
    }

    fun exchange(method: String, endpoint: String, input: InputStream?, size: Long, destination: OutputStream) {
        require(endpoint.startsWith("/localapi/v0/") && !endpoint.contains('\r') && !endpoint.contains('\n'))
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = timeoutMillis
            val output = socket.outputStream
            val headers = "$method $endpoint HTTP/1.1\r\nHost: local-tailscaled.sock\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: $size\r\n\r\n"
            output.write(headers.toByteArray(Charsets.US_ASCII))
            input?.copyTo(output)
            output.flush()
            val response = BufferedInputStream(socket.inputStream)
            val status = readLine(response).split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid LocalAPI response")
            val fields = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(response)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) fields[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            val target = if (status in 200..299) destination else ByteArrayOutputStream()
            if (fields["transfer-encoding"]?.lowercase() == "chunked") {
                while (true) {
                    val count = readLine(response).substringBefore(';').toLong(16)
                    if (count == 0L) break
                    copyExactly(response, target, count)
                    check(readLine(response).isEmpty())
                }
            } else {
                val length = fields["content-length"]?.toLongOrNull()
                if (length != null) copyExactly(response, target, length) else response.copyTo(target)
            }
            if (status !in 200..299) error("LocalAPI $status: ${(target as ByteArrayOutputStream).toString(Charsets.UTF_8.name())}")
        }
    }

    private fun readLine(input: InputStream): String {
        val output = ByteArrayOutputStream()
        while (output.size() < 16_384) {
            val value = input.read()
            check(value >= 0) { "LocalAPI closed before response completed" }
            if (value == 10) return output.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            output.write(value)
        }
        error("LocalAPI response header is too long")
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        require(length >= 0)
        var remaining = length
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(count > 0) { "LocalAPI closed before response completed" }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
}
