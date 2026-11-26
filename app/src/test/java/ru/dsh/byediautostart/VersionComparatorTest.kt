package ru.dsh.byediautostart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `новая версия определяется`() {
        assertTrue(VersionComparator.isNewer("v.1.8.3", "1.8.2"))
        assertTrue(VersionComparator.isNewer("1.9", "1.8.9"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `та же версия не обновление`() {
        assertFalse(VersionComparator.isNewer("v.1.8.2", "1.8.2"))
        assertFalse(VersionComparator.isNewer("1.8", "1.8.0"))
    }

    @Test
    fun `старая версия не обновление`() {
        assertFalse(VersionComparator.isNewer("1.7.9", "1.8.2"))
    }

    @Test
    fun `тег нормализуется`() {
        assertTrue(VersionComparator.normalize("v.1.8.2") == "1.8.2")
    }
}
