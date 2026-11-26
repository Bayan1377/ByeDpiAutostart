package ru.dsh.byediautostart

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** История уведомлений внутри приложения: чтобы важное не терялось в шторке. */
object AlertLog {

    private const val PREFS = "alert_log"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 100

    data class Entry(
        val time: Long,
        val type: String,
        val title: String,
        val text: String,
        val read: Boolean,
    )

    @Synchronized
    fun add(context: Context, type: String, title: String, text: String) {
        val now = System.currentTimeMillis()
        val entries = load(context).toMutableList()
        val latest = entries.firstOrNull()
        if (latest != null &&
            latest.type == type &&
            latest.title == title &&
            latest.text == text &&
            now - latest.time < 60_000L
        ) {
            return
        }
        entries.add(0, Entry(now, type, title, text, false))
        save(context, entries.take(MAX_ENTRIES))
    }

    @Synchronized
    fun all(context: Context): List<Entry> = load(context)

    fun latest(context: Context): Entry? = all(context).firstOrNull()

    fun unreadCount(context: Context): Int = all(context).count { !it.read }

    @Synchronized
    fun markAllRead(context: Context) {
        val updated = load(context).map { it.copy(read = true) }
        save(context, updated)
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Entry(
                    time = item.optLong("time"),
                    type = item.optString("type"),
                    title = item.optString("title"),
                    text = item.optString("text"),
                    read = item.optBoolean("read", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("time", entry.time)
                    .put("type", entry.type)
                    .put("title", entry.title)
                    .put("text", entry.text)
                    .put("read", entry.read)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
