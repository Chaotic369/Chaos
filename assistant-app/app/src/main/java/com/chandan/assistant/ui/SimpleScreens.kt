package com.chandan.assistant.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chandan.assistant.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ActivityScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val log by vm.log.collectAsState()
    var open by remember { mutableStateOf<Long?>(null) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    if (log.isEmpty()) {
        EmptyState(Icons.Filled.Bolt, "No activity yet", "Every command you run shows up here with timing.")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Activity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val text = log.joinToString("\n") {
                    "${fmt.format(Date(it.time))} [${it.route}] ${if (it.success) "OK" else "FAIL"} ${it.ms}ms  ${it.command} -> ${it.action}  ${it.detail}"
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                ctx.startActivity(Intent.createChooser(send, "Export log"))
            }) { Icon(Icons.Filled.Share, contentDescription = "Export") }
            IconButton(onClick = { vm.clearLog() }) { Icon(Icons.Filled.Delete, contentDescription = "Clear") }
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(log, key = { it.id }) { e ->
                val color = if (e.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Card(
                    Modifier.fillMaxWidth().clickable { open = if (open == e.id) null else e.id },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (e.route == "ai") Icons.Filled.AutoAwesome else Icons.Filled.Bolt, contentDescription = e.route, tint = color)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.command, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${fmt.format(Date(e.time))} · ${e.route} · ${e.ms} ms",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(if (e.success) "OK" else "FAIL", color = color)
                        }
                        if (open == e.id) {
                            Spacer(Modifier.height(8.dp))
                            Text("Action: ${e.action}", style = MaterialTheme.typography.bodySmall)
                            Text("Result: ${e.detail}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
