// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.amiantos.lurker.model.Backend
import net.amiantos.lurker.model.Buffer
import net.amiantos.lurker.model.BufferKey
import net.amiantos.lurker.model.BufferKind
import net.amiantos.lurker.model.EventType
import net.amiantos.lurker.model.Message
import net.amiantos.lurker.store.ChatState
import net.amiantos.lurker.ui.ChatViewModel
import net.amiantos.lurker.ui.SessionState
import net.amiantos.lurker.ui.theme.LurkerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LurkerTheme {
                val vm: ChatViewModel = viewModel()
                val session by vm.session.collectAsStateWithLifecycle()
                val chat by vm.chatState.collectAsStateWithLifecycle()
                var openKey by remember { mutableStateOf<BufferKey?>(null) }

                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val mod = Modifier.padding(padding).fillMaxSize()
                    when {
                        session != SessionState.LoggedIn -> LoginScreen(vm, mod)
                        openKey == null -> BufferListScreen(chat, mod) { key ->
                            vm.openBuffer(key)
                            openKey = key
                        }
                        else -> ChatScreen(chat, openKey!!, mod, onSend = { vm.send(openKey!!, it) }) {
                            openKey = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(vm: ChatViewModel, modifier: Modifier) {
    val status by vm.status.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()

    var backend by remember { mutableStateOf(Backend.SelfHosted) }
    var server by remember { mutableStateOf(Backend.SelfHosted.defaultUrl) }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Lurker", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Signs in with a password and opens the WebSocket with a bearer token.",
            style = MaterialTheme.typography.bodySmall,
        )

        // Backend picker. Switching resets the server URL to that backend's default
        // (unless the field was hand-edited off both defaults).
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Backend.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = backend == option,
                    onClick = {
                        if (server == backend.defaultUrl) server = option.defaultUrl
                        backend = option
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, Backend.entries.size),
                ) {
                    Text(if (option == Backend.Hosted) "Hosted" else "Self-hosted")
                }
            }
        }

        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = identifier,
            onValueChange = { identifier = it },
            label = { Text(backend.identifierLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.login(backend, server, identifier, password) },
            enabled = session != SessionState.LoggingIn &&
                identifier.isNotBlank() && password.isNotBlank(),
        ) {
            Text(if (session == SessionState.LoggingIn) "Signing in…" else "Sign in")
        }

        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun BufferListScreen(
    chat: ChatState,
    modifier: Modifier,
    onOpen: (BufferKey) -> Unit,
) {
    // Group under their network, system/server buffers last; stable ordering.
    val rows = remember(chat.buffers, chat.networks) {
        chat.buffers.values.sortedWith(
            compareBy({ it.networkId ?: Int.MAX_VALUE }, { it.target }),
        )
    }

    Column(modifier) {
        Text(
            if (chat.connected) "Connected" else "Connecting…",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()
        if (rows.isEmpty()) {
            Text("No buffers yet.", modifier = Modifier.padding(16.dp))
        }
        LazyColumn {
            items(rows, key = { it.key.id }) { buffer ->
                val networkName = buffer.networkId?.let { chat.networks[it]?.name } ?: "system"
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(buffer.key) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(buffer.target, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        networkName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ChatScreen(
    chat: ChatState,
    key: BufferKey,
    modifier: Modifier,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var draft by remember { mutableStateOf("") }
    val buffer: Buffer? = chat.buffers[key.id]
    val messages = (chat.messages[key.id] ?: emptyList()).filter { it.type.isRenderable }
    val listState = rememberLazyListState()

    // Follow the tail as messages land (hydration swap-in, then live events).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(modifier.imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Buffers") }
            Text(key.target, style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()

        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(messages.size) { index ->
                MessageRow(messages[index])
            }
        }

        // The system buffer (and unjoined server buffers) can't be posted to.
        if (key.networkId != null && buffer?.kind != BufferKind.Server) {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message ${key.target}") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            onSend(text)
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageRow(msg: Message) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        val label = when (msg.type) {
            EventType.Action -> "* ${msg.nick.orEmpty()}"
            EventType.System, EventType.Error -> "—"
            else -> msg.nick.orEmpty()
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (msg.self) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        Text("  ${msg.text.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
    }
}

/** The event types this foundation renders. Structural churn (join/part/…) is deferred. */
private val EventType.isRenderable: Boolean
    get() = isSpeech || this == EventType.Error || this == EventType.System
