package com.follow.clash.common

object SocketProtection {
    @Volatile
    var protect: ((Int) -> Boolean)? = null
}
