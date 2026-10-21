package com.chandan.assistant.core

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Optional. Everything here fails soft so the app works fully without Shizuku. */
object ShizukuShell {
    fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun available(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(1001) } catch (e: Throwable) { }
    }

    /** Runs a shell command as the shell user. Returns exit code and combined output. */
    suspend fun exec(cmd: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            code to (out + err).trim()
        } catch (e: Throwable) {
            -1 to (e.message ?: "Shizuku command failed")
        }
    }
}
