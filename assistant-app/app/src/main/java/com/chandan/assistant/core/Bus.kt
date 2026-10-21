package com.chandan.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** Shared busy flag and stop handlers between the UI and the background service. */
object Bus {
    val busy = MutableStateFlow(false)
    private val owners = mutableSetOf<String>()
    private val stoppers = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun setBusy(owner: String, b: Boolean) {
        if (b) owners.add(owner) else owners.remove(owner)
        busy.value = owners.isNotEmpty()
    }

    fun addStopper(f: () -> Unit) { stoppers.add(f) }
    fun removeStopper(f: () -> Unit) { stoppers.remove(f) }
    fun stopAll() { stoppers.forEach { it() } }
}
