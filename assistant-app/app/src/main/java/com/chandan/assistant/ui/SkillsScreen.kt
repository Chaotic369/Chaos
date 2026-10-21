package com.chandan.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chandan.assistant.MainViewModel
import com.chandan.assistant.core.Profiles
import com.chandan.assistant.core.ai.AgentStep
import com.chandan.assistant.core.parseSteps
import com.chandan.assistant.core.stepsJson
import com.chandan.assistant.data.Skill

private fun stepsToText(json: String): String = parseSteps(json).joinToString("\n") { s ->
    listOf(s.action, if (s.action == "scroll") s.direction else s.target, s.text).joinToString(" | ").trimEnd(' ', '|')
}

private fun textToSteps(t: String): String = stepsJson(
    t.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val p = line.split("|").map { it.trim() }
        val a = p.getOrElse(0) { "wait" }
        val second = p.getOrElse(1) { "" }
        AgentStep(
            action = a, target = if (a == "scroll") "" else second,
            direction = if (a == "scroll") second else "", text = p.getOrElse(2) { "" }
        )
    }
)

@Composable
fun SkillsScreen(vm: MainViewModel) {
    val skills by vm.skills.collectAsState()
    val teaching by vm.teaching.collectAsState()
    var teachDialog by remember { mutableStateOf(false) }
    var chainDialog by remember { mutableStateOf(false) }
    var runTarget by remember { mutableStateOf<Skill?>(null) }
    var editTarget by remember { mutableStateOf<Skill?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (teaching) {
                item {
                    Card(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Text("Recording… do the task in your apps, then tap the green DONE bubble.", Modifier.padding(16.dp))
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("App profiles", style = MaterialTheme.typography.titleMedium)
                        Profiles.seed.forEach { Text("${it.appName} · ${it.category}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (skills.isEmpty()) {
                item {
                    Text(
                        "No skills yet. Tap \"Teach new skill\", or say \"learn this as <name>\".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(skills, key = { it.id }) { sk ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(sk.name, style = MaterialTheme.typography.titleMedium)
                        if (sk.description.isNotBlank()) Text(sk.description, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Params: ${sk.params.ifBlank { "none" }} · ${parseSteps(sk.steps).size} steps",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                if (sk.params.isBlank()) vm.runSkillFromUi(sk.name, emptyMap()) else runTarget = sk
                            }) { Text("Run") }
                            TextButton(onClick = { editTarget = sk }) { Text("Edit") }
                            IconButton(onClick = { vm.deleteSkill(sk) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = { chainDialog = true }) { Text("New chain (e.g. morning routine)") } }
        }
        ExtendedFloatingActionButton(
            onClick = { teachDialog = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Teach new skill") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }

    if (teachDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { teachDialog = false },
            title = { Text("Teach new skill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recording starts when you tap Start. Switch to the app, do the task once, then tap the green DONE bubble.")
                    OutlinedTextField(name, { name = it }, label = { Text("Name (optional, e.g. ask claude)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.submit(if (name.isBlank()) "learn this" else "learn this as ${name.trim()}")
                    teachDialog = false
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { teachDialog = false }) { Text("Cancel") } }
        )
    }

    runTarget?.let { sk ->
        val params = sk.params.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val values = remember(sk.id) { mutableStateOf(params.associateWith { "" }) }
        AlertDialog(
            onDismissRequest = { runTarget = null },
            title = { Text("Run ${sk.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    params.forEach { p ->
                        OutlinedTextField(
                            values.value[p].orEmpty(), { values.value = values.value + (p to it) },
                            label = { Text(p) }, singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.runSkillFromUi(sk.name, values.value)
                    runTarget = null
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { runTarget = null }) { Text("Cancel") } }
        )
    }

    editTarget?.let { sk ->
        var name by remember(sk.id) { mutableStateOf(sk.name) }
        var desc by remember(sk.id) { mutableStateOf(sk.description) }
        var params by remember(sk.id) { mutableStateOf(sk.params) }
        var steps by remember(sk.id) { mutableStateOf(stepsToText(sk.steps)) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit skill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(desc, { desc = it }, label = { Text("Description") })
                    OutlinedTextField(params, { params = it }, label = { Text("Params (comma separated)") }, singleLine = true)
                    OutlinedTextField(
                        steps, { steps = it }, label = { Text("Steps: action | target | text") }, minLines = 4
                    )
                    Text("Actions: open_app, click, type, enter, scroll (target=up/down), back, home, wait, run_skill. Use {param} inside text.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    if (n.isNotEmpty()) {
                        vm.saveSkill(sk.copy(name = n, description = desc.trim(), params = params.trim(), steps = textToSteps(steps)))
                    }
                    editTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } }
        )
    }

    if (chainDialog) {
        var name by remember { mutableStateOf("") }
        var list by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { chainDialog = false },
            title = { Text("New chain") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Chain name (morning routine)") }, singleLine = true)
                    OutlinedTextField(list, { list = it }, label = { Text("Skills in order, comma separated") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createChain(name, list.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    chainDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { chainDialog = false }) { Text("Cancel") } }
        )
    }
}
