package org.meshtastic.aether.visual

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.meshtastic.aether.theme.LocalAmbientPalette
import org.meshtastic.aether.ui.AmbientEngine

/** The full-screen ambient scene: gradient, haze, orbs and ripples, redrawn every frame on the audio clock. */
@Composable
fun AmbientScene(engine: AmbientEngine, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    val scene = engine.scene
    val renderer = remember { SceneRenderer() }
    LaunchedEffect(engine) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { scene.frameStats.record(it) }
            scene.update(engine.playbackFrame())
            frame++
        }
    }
    val palette = LocalAmbientPalette.current
    Canvas(modifier) {
        frame.let { }
        renderer.draw(this, engine, scene, palette)
    }
}
