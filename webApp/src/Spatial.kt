@file:OptIn(ExperimentalWasmJsInterop::class)

package org.meshtastic.aether

import org.meshtastic.aether.visual.SpatialFrame
import org.meshtastic.aether.visual.SpatialSource
import kotlin.js.ExperimentalWasmJsInterop

/** No headset or AR session can be opened here. */
internal const val SPATIAL_NONE = 0

@JsFun("(found) => window.aetherSpatial.probe(found)")
private external fun probe(found: (Int) -> Unit)

@JsFun("(mode, onFrame, onEnd) => window.aetherSpatial.start(mode, onFrame, onEnd)")
private external fun start(mode: Int, onFrame: () -> Unit, onEnd: () -> Unit)

@JsFun("(sprites, lines) => window.aetherSpatial.reserve(sprites, lines)")
private external fun reserve(spriteFloats: Int, lineFloats: Int)

@JsFun("(lineArray, index, value) => window.aetherSpatial.put(lineArray, index, value)")
private external fun put(lineArray: Boolean, index: Int, value: Float)

@JsFun("(sprites, lineVertices, r, g, b) => window.aetherSpatial.commit(sprites, lineVertices, r, g, b)")
private external fun commit(sprites: Int, lineVertices: Int, r: Float, g: Float, b: Float)

/** Reports what `spatial.js` can open in this browser: [SPATIAL_NONE], or the mode to hand [enterSpatial]. */
internal fun probeSpatial(found: (Int) -> Unit) = probe(found)

/** Opens the WebXR session for [mode] and feeds it [source]'s frame before each one it draws; call from a click. */
internal fun enterSpatial(mode: Int, source: SpatialSource) {
    start(mode, onFrame = { push(source.frame()) }, onEnd = {})
}

private fun push(frame: SpatialFrame) {
    val spriteFloats = frame.spriteCount * SpatialFrame.SPRITE_STRIDE
    val lineFloats = frame.lineVertexCount * SpatialFrame.LINE_STRIDE
    reserve(frame.sprites.size, frame.lines.size)
    val sprites = frame.sprites
    for (i in 0 until spriteFloats) put(false, i, sprites[i])
    val lines = frame.lines
    for (i in 0 until lineFloats) put(true, i, lines[i])
    commit(frame.spriteCount, frame.lineVertexCount, frame.background[0], frame.background[1], frame.background[2])
}
