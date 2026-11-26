package org.meshtastic.aether.update

// The web build is whatever the page serves, so it never updates itself.
internal actual fun createUpdater(): Updater? = null
