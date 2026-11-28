package org.meshtastic.aether

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import org.meshtastic.aether.ui.PaneChrome
import org.meshtastic.aether.ui.PaneInput
import org.meshtastic.aether.ui.SettingsContent
import org.meshtastic.aether.ui.SettingsList
import org.meshtastic.aether.ui.SettingsTopic
import org.meshtastic.aether.ui.SettingsTopicPane

/** The pane layout the window allows; the scene strategy and the settings panes read the same one. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun paneDirective(): PaneScaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())

/** Whether [directive] shows a settings topic beside its list. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun showsTwoPanes(directive: PaneScaffoldDirective): Boolean = directive.maxHorizontalPartitions > 1

/** Replaces the whole stack with [screen]. */
internal fun MutableList<Screen>.reset(screen: Screen) {
    clear()
    add(screen)
}

/** Shows [topic]: one already showing is replaced rather than stacked, so Back returns to the list. */
internal fun MutableList<Screen>.showTopic(topic: SettingsTopic) {
    val next = Screen.SettingsDetail(topic)
    if (lastOrNull() is Screen.SettingsDetail) set(lastIndex, next) else add(next)
}

/** Leaves Settings: the list and any topic beside or over it. */
internal fun MutableList<Screen>.closeSettings() {
    removeAll { it == Screen.SettingsHome || it is Screen.SettingsDetail }
}

/** The topic showing beside the list, if any. */
internal fun List<Screen>.shownTopic(): SettingsTopic? = (lastOrNull() as? Screen.SettingsDetail)?.topic

/**
 * Follows the session: while [session] plays the stack is the scene alone, so Back reaches the scene's own handler,
 * and when it stops the stack returns to the connect screen.
 */
internal fun MutableList<Screen>.follow(session: Session?) {
    val top = lastOrNull()
    when {
        session != null && top != Screen.Playing(session) -> reset(Screen.Playing(session))
        session == null && top is Screen.Playing -> reset(Screen.Connect)
    }
}

/** The back stack shown through Navigation 3, laid out by [directive]: a settings topic beside its list when wide. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun AppNavDisplay(
    backStack: MutableList<Screen>,
    directive: PaneScaffoldDirective,
    entries: EntryProviderScope<Screen>.() -> Unit,
) {
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        sceneStrategies = listOf(rememberListDetailSceneStrategy(directive = directive)),
        entryProvider = entryProvider(builder = entries),
    )
}

/**
 * The settings list and its topics. The list's Back leaves Settings. Beside the list ([twoPanes]) a topic has no Back
 * of its own, the list marks it, and with none open a note fills the empty pane; on a narrow window a topic covers
 * the list and its own Back returns to it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun EntryProviderScope<Screen>.settingsEntries(
    backStack: MutableList<Screen>,
    content: () -> SettingsContent,
    twoPanes: () -> Boolean,
    input: PaneInput,
    modifier: Modifier,
) {
    entry<Screen.SettingsHome>(metadata = { ListDetailSceneStrategy.listPane(detailPlaceholder = { NoTopic() }) }) {
        val selected = if (twoPanes()) backStack.shownTopic() else null
        val chrome = PaneChrome(backStack::closeSettings, input)
        SettingsList(content(), selected, backStack::showTopic, chrome, modifier)
    }
    entry<Screen.SettingsDetail>(metadata = { ListDetailSceneStrategy.detailPane() }) { key ->
        val beside = twoPanes()
        val pop: () -> Unit = { backStack.removeAt(backStack.lastIndex) }
        val topicInput = PaneInput(focusBack = input.focusBack && !beside, tooltips = input.tooltips)
        SettingsTopicPane(key.topic, content(), PaneChrome(pop, topicInput, showBack = !beside), modifier)
    }
}

@Composable
private fun NoTopic() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Choose a topic",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The app's destinations; the top of the back stack is what shows, with a settings topic beside its list when wide. */
internal sealed interface Screen {
    /** The browser needs a gesture before it lets the page play sound. */
    data object TapToStart : Screen

    data object Connect : Screen

    /** The settings list, the list pane of the settings scene. */
    data object SettingsHome : Screen

    /** One settings topic, the detail pane beside or over the list. */
    data class SettingsDetail(val topic: SettingsTopic) : Screen

    /** The scene, playing [session]. */
    data class Playing(val session: Session) : Screen
}
