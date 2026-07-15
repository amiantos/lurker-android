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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.amiantos.lurker.ui.theme.LurkerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LurkerTheme {
                val client = remember { LurkerClient() }
                var open by remember { mutableStateOf<Buffer?>(null) }

                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val mod = Modifier.padding(padding).fillMaxSize()
                    when {
                        !client.loggedIn -> LoginScreen(client, mod)
                        open == null -> BufferListScreen(client, mod) { buffer ->
                            client.open(buffer)
                            open = buffer
                        }
                        else -> ChatScreen(client, open!!, mod) { open = null }
                    }
                }
            }
        }
    }
}

// The two backends a Lurker client can speak to. Self-hosted mints its token at
// the cell; hosted mints at the control plane and rides the proxy. See
// LurkerClient.login.
private const val HOSTED_URL = "https://app.lurker.chat"

// 10.0.2.2 is the emulator's alias for the HOST machine's 127.0.0.1 (plain
// "localhost" would be the emulator itself). 8010 is the API/WS server — NOT the
// Vite client dev port, which only serves the web SPA.
private const val SELF_HOSTED_URL = "http://10.0.2.2:8010"

@Composable
private fun LoginScreen(client: LurkerClient, modifier: Modifier) {
    var hosted by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(SELF_HOSTED_URL) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Lurker", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Prototype client — signs in with a password and opens the WebSocket with a bearer token.",
            style = MaterialTheme.typography.bodySmall,
        )
        // Backend picker. Switching resets the server URL to that backend's
        // default (unless the field has been hand-edited off both defaults).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackendChip("Self-hosted", selected = !hosted) {
                hosted = false
                if (server == HOSTED_URL) server = SELF_HOSTED_URL
            }
            BackendChip("Hosted (lurker.chat)", selected = hosted) {
                hosted = true
                if (server == SELF_HOSTED_URL) server = HOSTED_URL
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
            value = username,
            onValueChange = { username = it },
            // Hosted authenticates by account email; self-hosted by IRC-side username.
            label = { Text(if (hosted) "Email" else "Username") },
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
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { client.login(server, username, password, hosted) }
                }
            },
            enabled = username.isNotBlank() && password.isNotBlank(),
        ) { Text("Sign in") }

        client.status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun BackendChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        TextButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun BufferListScreen(
    client: LurkerClient,
    modifier: Modifier,
    onOpen: (Buffer) -> Unit,
) {
    Column(modifier) {
        Text(
            if (client.connected) "Connected" else "Connecting…",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()
        if (client.buffers.isEmpty()) {
            Text("No buffers yet.", modifier = Modifier.padding(16.dp))
        }
        LazyColumn {
            items(client.buffers, key = { it.key }) { buffer ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(buffer) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(buffer.target, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        buffer.networkName,
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
    client: LurkerClient,
    buffer: Buffer,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var draft by remember { mutableStateOf("") }
    val messages = client.messagesByBuffer[buffer.key] ?: emptyList()
    val listState = rememberLazyListState()

    // Follow the tail as messages land (backlog swap-in, then live events).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(modifier.imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Buffers") }
            Text(buffer.target, style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()

        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(messages, key = { it.id }) { msg ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        if (msg.type == "action") "* ${msg.nick}" else msg.nick,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (msg.self) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    )
                    Text(
                        "  ${msg.text}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (buffer.networkId != null) {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message ${buffer.target}") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            client.send(buffer, text)
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}
