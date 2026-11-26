package ru.dsh.byediautostart

/** Пакеты целевых приложений, для которых есть особые правила. */
object TargetPackages {

    val TELEGRAM: Set<String> = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.telegram.messenger",
    )

    fun isTelegram(pkg: String): Boolean = pkg in TELEGRAM
}
