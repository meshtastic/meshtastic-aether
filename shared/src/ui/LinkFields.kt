@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val PortFieldWidth = 120.dp

/**
 * The pane for [selected]: the demo's note, the TCP or WebSocket fields, or the Bluetooth or USB radio list. [onEdit]
 * runs on every edit or pick (it cancels a pending reconnect); [choose] starts the link with the pane it came from.
 */
@Composable
internal fun LinkFields(
    selected: LinkKind,
    form: ConnectForm,
    sideBySide: Boolean,
    onEdit: () -> Unit,
    choose: (LinkKind, LinkChoice) -> Unit,
) {
    val link = currentLink(selected, form.host, form.port, form.ws)
    val submit = { link?.let { choose(selected, it) } ?: Unit }
    when (selected) {
        LinkKind.Demo -> Text(
            "Plays the scripted demo mesh. No radio needed.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LinkKind.Network -> NetworkFields(form, sideBySide, onEdit, submit)

        LinkKind.Bluetooth -> BluetoothSection({ choose(LinkKind.Bluetooth, LinkChoice.Ble(it)) }, onInteract = onEdit)

        LinkKind.Serial -> SerialSection(
            { choose(LinkKind.Serial, LinkChoice.Serial(it.path, it.label)) },
            onInteract = onEdit,
        )

        LinkKind.WebSocket -> WebSocketField(form, onEdit, submit)
    }
}

/** Host and port, side by side when there is room; Go on the port submits. */
@Composable
private fun NetworkFields(form: ConnectForm, sideBySide: Boolean, onEdit: () -> Unit, submit: () -> Unit) {
    val entry = tcpEntry(form.host, form.port)
    val hostError = if (form.host.isBlank()) HOST_ERROR else null
    val portError = (entry as? TcpEntry.Invalid)?.message?.takeIf { hostError == null }
    val hostField: @Composable (Modifier) -> Unit = { m ->
        OutlinedTextField(
            form.host,
            {
                onEdit()
                form.host = it
            },
            m.errorSemantics(hostError),
            label = { Text("Node host") },
            singleLine = true,
            isError = hostError != null,
            supportingText = hostError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
    }
    val portField: @Composable (Modifier) -> Unit = { m ->
        OutlinedTextField(
            form.port,
            {
                onEdit()
                form.port = it.filter(Char::isDigit)
            },
            m.errorSemantics(portError),
            label = { Text("Port") },
            singleLine = true,
            isError = portError != null,
            supportingText = portError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
    }
    if (sideBySide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            hostField(Modifier.weight(1f))
            portField(Modifier.width(PortFieldWidth))
        }
    } else {
        hostField(Modifier.fillMaxWidth())
        portField(Modifier.fillMaxWidth())
    }
}

/** The WebSocket URL; Go submits. */
@Composable
private fun WebSocketField(form: ConnectForm, onEdit: () -> Unit, submit: () -> Unit) {
    val ws = form.ws
    val wsError = webSocketError(ws).takeIf { ws.isNotBlank() } ?: if (ws.isBlank()) WS_BLANK_ERROR else null
    OutlinedTextField(
        ws,
        {
            onEdit()
            form.ws = it
        },
        Modifier.fillMaxWidth().errorSemantics(wsError),
        label = { Text("WebSocket URL") },
        singleLine = true,
        isError = wsError != null,
        supportingText = wsError?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { submit() }),
    )
}

/** Names a field's own error to a screen reader; Material only marks a field in error with a generic message. */
private fun Modifier.errorSemantics(message: String?): Modifier =
    if (message == null) this else semantics { error(message) }

/** Connect over the pane's link, enabled once the fields describe one. */
@Composable
internal fun PrimaryButton(selected: LinkKind, link: LinkChoice?, onConnect: (LinkChoice) -> Unit) {
    Button(
        { link?.let(onConnect) },
        ButtonDefaults.shapes(),
        Modifier.fillMaxWidth().heightIn(min = MinTouch),
        enabled = link != null,
    ) {
        Text(
            when (selected) {
                LinkKind.Demo -> "Start the demo"
                LinkKind.Network -> "Connect over TCP"
                else -> "Connect over WebSocket"
            },
        )
    }
}
