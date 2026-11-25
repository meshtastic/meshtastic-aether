package org.meshtastic.ambient

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
