package ru.dsh.byediautostart

/** Сравнение версий ByeByeDPI вида `v.1.8.2` и `1.8.2`. */
object VersionComparator {

    fun isNewer(latest: String, installed: String): Boolean {
        val a = parts(latest)
        if (a.isEmpty()) return false
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    fun normalize(version: String): String = parts(version).joinToString(".")

    private fun parts(version: String): List<Int> =
        Regex("\\d+").findAll(version).map { it.value.toInt() }.toList()
}
