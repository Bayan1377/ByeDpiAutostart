package ru.dsh.byediautostart

/**
 * Чистая логика «какое приложение на экране» без Android API — её проверяют unit-тесты.
 *
 * Целевое приложение включает VPN. Любое другое приложение (включая рабочий стол) запускает
 * отложенное выключение; нейтральные пакеты (SystemUI, клавиатура, диалоги разрешений)
 * не считаются уходом, иначе VPN гас бы при каждом сворачивании шторки.
 */
class AutomationCore(
    private val targets: Set<String>,
    private val neutral: Set<String>,
    private val graceMs: Long,
) {

    enum class Foreground { ENTERED_TARGET, LEFT_TARGET, NEUTRAL, NONE }

    var targetActive: Boolean = false
        private set

    var lastTargetPackage: String? = null
        private set

    private var stopAt: Long? = null

    fun onForeground(pkg: String, now: Long): Foreground {
        if (pkg in neutral) return Foreground.NEUTRAL

        if (pkg in targets) {
            val wasActive = targetActive
            targetActive = true
            stopAt = null
            lastTargetPackage = pkg
            return if (wasActive) Foreground.NONE else Foreground.ENTERED_TARGET
        }

        if (!targetActive) return Foreground.NONE

        targetActive = false
        stopAt = now + graceMs
        return Foreground.LEFT_TARGET
    }

    /** Пора ли гасить VPN: пауза после ухода истекла и целевое приложение не вернулось. */
    fun shouldStop(now: Long): Boolean {
        if (targetActive) return false
        val deadline = stopAt ?: return false
        return now >= deadline
    }

    /** Есть ли вообще что отслеживать: цель на экране или тикающая пауза после ухода. */
    fun hasState(): Boolean = targetActive || stopAt != null

    fun pendingStopAt(): Long? = stopAt

    /** Переносит состояние в новый экземпляр при смене списка приложений. */
    fun restore(active: Boolean, pkg: String?, deadline: Long?) {
        targetActive = active
        lastTargetPackage = pkg
        stopAt = deadline
    }

    fun markStopped() {
        stopAt = null
    }

    fun reset() {
        targetActive = false
        stopAt = null
        lastTargetPackage = null
    }
}
