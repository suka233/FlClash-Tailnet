package com.follow.clash.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import com.follow.clash.common.SocketProtection
import java.io.Closeable
import java.util.concurrent.Executors

class ProtectionBroker(context: Context, val name: String) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val listener = LocalServerSocket(name)
    private val workers = Executors.newFixedThreadPool(8)
    @Volatile private var owner: LocalSocket? = null

    init {
        workers.execute {
            while (!workers.isShutdown) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                workers.execute {
                    socket.use {
                        runCatching {
                            check(socket.peerCredentials.uid == Process.myUid())
                            socket.soTimeout = 5_000
                            val request = socket.inputStream.read()
                            if (request == 2) {
                                check(owner == null)
                                owner = socket
                                socket.outputStream.write(1)
                                socket.soTimeout = 0
                                socket.inputStream.read()
                                return@execute
                            }
                            check(request == 1)
                            val descriptors = socket.ancillaryFileDescriptors ?: error("No socket descriptor received")
                            check(descriptors.size == 1)
                            try { ParcelFileDescriptor.dup(descriptors[0]).use { duplicate ->
                                val protect = SocketProtection.protect
                                val network = connectivity.allNetworks.firstOrNull { candidate ->
                                    val capabilities = connectivity.getNetworkCapabilities(candidate)
                                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                                }
                                network?.bindSocket(duplicate.fileDescriptor)
                                val protected = protect?.invoke(duplicate.fd) ?: true
                                socket.outputStream.write(if (protected) 1 else 0)
                            } } finally { descriptors.forEach { runCatching { Os.close(it) } } }
                        }.onFailure { runCatching { socket.outputStream.write(0) } }
                    }
                }
            }
        }
    }

    override fun close() {
        owner?.close()
        owner = null
        listener.close()
        workers.shutdownNow()
    }
}
