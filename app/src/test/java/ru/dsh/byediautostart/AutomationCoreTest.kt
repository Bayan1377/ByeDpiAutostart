package ru.dsh.byediautostart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCoreTest {

    private val youtube = "com.google.android.youtube"
    private val instagram = "com.instagram.android"
    private val launcher = "com.miui.home"
    private val systemUi = "com.android.systemui"

    private fun core() = AutomationCore(
        targets = setOf(youtube, instagram),
        neutral = setOf(systemUi, "com.google.android.inputmethod.latin"),
        graceMs = 15_000L,
    )

    @Test
    fun `целевое приложение включает режим`() {
        val c = core()
        assertEquals(AutomationCore.Foreground.ENTERED_TARGET, c.onForeground(youtube, 0))
        assertTrue(c.targetActive)
        assertEquals(youtube, c.lastTargetPackage)
    }

    @Test
    fun `переключение между двумя целевыми не выключает VPN`() {
        val c = core()
        c.onForeground(youtube, 0)
        assertEquals(AutomationCore.Foreground.NONE, c.onForeground(instagram, 1_000))
        assertTrue(c.targetActive)
        assertFalse(c.shouldStop(999_999))
    }

    @Test
    fun `уход запускает паузу 15 секунд`() {
        val c = core()
        c.onForeground(youtube, 0)
        assertEquals(AutomationCore.Foreground.LEFT_TARGET, c.onForeground(launcher, 1_000))
        assertFalse(c.shouldStop(15_999))
        assertTrue(c.shouldStop(16_000))
    }

    @Test
    fun `возврат в приложение отменяет выключение`() {
        val c = core()
        c.onForeground(youtube, 0)
        c.onForeground(launcher, 1_000)
        assertEquals(AutomationCore.Foreground.ENTERED_TARGET, c.onForeground(youtube, 5_000))
        assertFalse(c.shouldStop(999_999))
    }

    @Test
    fun `шторка и клавиатура не считаются уходом`() {
        val c = core()
        c.onForeground(youtube, 0)
        assertEquals(AutomationCore.Foreground.NEUTRAL, c.onForeground(systemUi, 2_000))
        assertTrue(c.targetActive)
        assertFalse(c.shouldStop(999_999))
    }

    @Test
    fun `уход без активной цели ничего не запускает`() {
        val c = core()
        assertEquals(AutomationCore.Foreground.NONE, c.onForeground(launcher, 0))
        assertFalse(c.shouldStop(999_999))
    }

    @Test
    fun `после остановки дедлайн сбрасывается`() {
        val c = core()
        c.onForeground(youtube, 0)
        c.onForeground(launcher, 1_000)
        assertTrue(c.shouldStop(20_000))
        c.markStopped()
        assertFalse(c.shouldStop(30_000))
    }

    @Test
    fun `сброс очищает состояние`() {
        val c = core()
        c.onForeground(youtube, 0)
        c.reset()
        assertFalse(c.targetActive)
        assertNull(c.lastTargetPackage)
        assertFalse(c.shouldStop(999_999))
    }

    @Test
    fun `пауза переносится в новый экземпляр при смене списка`() {
        val old = core()
        old.onForeground(youtube, 0)
        old.onForeground(launcher, 1_000)

        val fresh = core()
        fresh.restore(old.targetActive, old.lastTargetPackage, old.pendingStopAt())

        assertFalse(fresh.targetActive)
        assertTrue(fresh.hasState())
        assertFalse(fresh.shouldStop(15_999))
        assertTrue(fresh.shouldStop(16_000))
    }

    @Test
    fun `без цели и паузы состояния нет`() {
        val c = core()
        assertFalse(c.hasState())
        c.onForeground(youtube, 0)
        assertTrue(c.hasState())
        c.onForeground(launcher, 1_000)
        assertTrue(c.hasState())
        c.markStopped()
        assertFalse(c.hasState())
    }
}
