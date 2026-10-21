package com.chandan.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chandan.assistant.MainViewModel
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.core.ShizukuShell

@Composable
fun StatusLine(label: String, on: Boolean, note: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            Modifier.size(10.dp).background(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape
            )
        )
        Spacer(Modifier.width(10.dp))
        Text(label + (if (note.isNotEmpty()) "  ($note)" else ""), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val listening by vm.voice.listening.collectAsState()
    val level by vm.voice.level.collectAsState()
    val transcript by vm.transcript.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val log by vm.log.collectAsState()
    val apiKey by vm.apiKey.collectAsState()
    var text by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val a11yOn = remember(tick) { AssistantAccessibilityService.instance != null }
    val shizukuOn = remember(tick) { ShizukuShell.available() }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startListening()
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseValue"
    )
    val ring = if (listening) pulse + level / 30f else 1f

    fun send() {
        if (text.isNotBlank()) {
            vm.submit(text)
            text = ""
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Status", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                StatusLine("Voice and fast path", true)
                StatusLine("Accessibility", a11yOn, if (a11yOn) "" else "enable in Settings")
                StatusLine("Shizuku", shizukuOn, if (shizukuOn) "" else "optional")
                StatusLine("AI key", apiKey.isNotBlank(), if (apiKey.isBlank()) "set in Settings" else "saved")
            }
        }

        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size((130 * ring).dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), CircleShape)
            )
            FilledIconButton(
                onClick = {
                    if (listening) {
                        vm.voice.stopListening()
                    } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.startListening()
                    } else {
                        micPerm.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.size(96.dp)
            ) {
                Icon(Icons.Filled.Mic, contentDescription = "Speak", modifier = Modifier.size(44.dp))
            }
        }

        Card(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (transcript.isEmpty()) "Your words appear here" else transcript,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (busy) {
            Button(
                onClick = { vm.stop() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("STOP")
            }
        }

        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Type a command, e.g. call raja") },
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                IconButton(onClick = { send() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        )

        val recent = log.map { it.command }.distinct().take(6)
        if (recent.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { c ->
                    AssistChip(onClick = { vm.submit(c) }, label = { Text(c) })
                }
            }
        }
    }
}
