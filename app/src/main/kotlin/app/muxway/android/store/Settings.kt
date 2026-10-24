package app.muxway.android.store

import android.content.Context

enum class Theme { GREEN, AMBER, PAPER }

data class Settings(
    val hubs: List<Hub> = emptyList(),
    val current: String = "",
    val refreshSeconds: Int = 2,
    val lines: Int = 200,
    val textSp: Int = 12,
    val theme: Theme = Theme.GREEN,
    val blockScreenshots: Boolean = false,
    val hardwareKeyboard: Boolean = false,
    val effects: Boolean = true,
    val reflow: Boolean = false,
    val watch: Boolean = false,
    val notifyText: Boolean = false,
    val watchAnswer: Boolean = false,
) {
    /** The hub in use, and its URL; empty until one is added. */
    val hub: Hub? get() = hubs.firstOrNull { it.id == current }
    val hubUrl: String get() = hub?.url ?: ""
}

/** Everything but the token, in plain app-private preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        var hubs = Hubs.decode(prefs.getString("hubs", null))
        var current = prefs.getString("current", "") ?: ""
        // An install from before the list had one hub; it keeps the id its token is adopted under.
        val legacy = prefs.getString("hub_url", "") ?: ""
        if (hubs.isEmpty() && legacy.isNotEmpty()) {
            hubs = listOf(Hub(Hubs.LEGACY_ID, Hubs.defaultName(legacy, emptyList()), legacy))
            current = Hubs.LEGACY_ID
        }
        if (hubs.none { it.id == current }) current = hubs.firstOrNull()?.id ?: ""
        return Settings(
            hubs = hubs,
            current = current,
            refreshSeconds = prefs.getInt("refresh", d.refreshSeconds).coerceIn(1, 60),
            lines = prefs.getInt("lines", d.lines).coerceIn(10, 2000),
            textSp = prefs.getInt("text_sp", d.textSp).coerceIn(6, 24),
            theme = runCatching { Theme.valueOf(prefs.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            blockScreenshots = prefs.getBoolean("block_screenshots", d.blockScreenshots),
            hardwareKeyboard = prefs.getBoolean("hardware_keyboard", d.hardwareKeyboard),
            effects = prefs.getBoolean("effects", d.effects),
            reflow = prefs.getBoolean("reflow", d.reflow),
            watch = prefs.getBoolean("watch", d.watch),
            notifyText = prefs.getBoolean("notify_text", d.notifyText),
            watchAnswer = prefs.getBoolean("watch_answer", d.watchAnswer),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("hubs", Hubs.encode(s.hubs))
            .putString("current", s.current)
            .remove("hub_url")
            .putInt("refresh", s.refreshSeconds)
            .putInt("lines", s.lines)
            .putInt("text_sp", s.textSp)
            .putString("theme", s.theme.name)
            .putBoolean("block_screenshots", s.blockScreenshots)
            .putBoolean("hardware_keyboard", s.hardwareKeyboard)
            .putBoolean("effects", s.effects)
            .putBoolean("reflow", s.reflow)
            .putBoolean("watch", s.watch)
            .putBoolean("notify_text", s.notifyText)
            .putBoolean("watch_answer", s.watchAnswer)
            .apply()
    }

    /** The last lines sent to a shell, newest first, for the command row. */
    fun recent(): List<String> = prefs.getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }

    fun saveRecent(lines: List<String>) {
        prefs.edit().putString("recent", lines.joinToString("\n")).apply()
    }
}
