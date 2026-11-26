package ru.dsh.byediautostart

/** То, что виджет и экран показывают без обращения к системным сервисам. */
object AutomationState {
    @Volatile var vpnUp: Boolean = false
    @Volatile var targetPackage: String? = null
    @Volatile var watchdogRunning: Boolean = false
    @Volatile var lastError: String? = null
}
