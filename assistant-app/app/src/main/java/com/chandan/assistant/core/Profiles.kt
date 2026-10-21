package com.chandan.assistant.core

data class AppProfile(
    val id: String,
    val appName: String,
    val aliases: List<String>,
    val pkg: String,
    val category: String
)

object Profiles {
    val seed = listOf(
        AppProfile("whatsapp", "WhatsApp", listOf("whatsapp", "watsapp", "wa"), "com.whatsapp", "messaging"),
        AppProfile("telegram", "Telegram", listOf("telegram", "tg"), "org.telegram.messenger", "messaging"),
        AppProfile("instagram", "Instagram", listOf("instagram", "insta", "ig"), "com.instagram.android", "social"),
        AppProfile("gmail", "Gmail", listOf("gmail", "email", "mail"), "com.google.android.gm", "email"),
        AppProfile("sms", "SMS", listOf("sms", "text", "message", "messages", "msg"), "", "messaging"),
        AppProfile("chrome", "Chrome", listOf("chrome", "browser"), "com.android.chrome", "browser")
    )

    fun find(name: String): AppProfile? {
        if (name.isBlank()) return null
        return seed.map { p -> p to (listOf(p.appName) + p.aliases).maxOf { Fuzzy.score(name, it) } }
            .filter { it.second >= 0.8 }.maxByOrNull { it.second }?.first
    }
}
