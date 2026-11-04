package app.muxway.android.store

import android.content.Context
import app.muxway.android.protocol.OpenMode

enum class Theme { AMBER, GREEN, PAPER }

data class Settings(
    val hubs: List<Hub> = emptyList(),
    val current: String = "",
    val refreshSeconds: Int = 2,
    val lines: Int = 200,
    val textSp: Int = 12,
    val theme: Theme = Theme.AMBER,
    val blockScreenshots: Boolean = false,
    val hardwareKeyboard: Boolean = false,
    val effects: Boolean = true,
    val reflow: Boolean = false,
    val watch: Boolean = false,
    val notifyText: Boolean = false,
    val watchAnswer: Boolean = false,
    /** The names of a shell pane's directory on its screen are links. */
    val names: Boolean = true,
    /** How a tapped file opens, and the person's own command when that is picked. */
    val openWith: OpenMode = OpenMode.PREVIEW,
    val openCommand: String = "",
    /** The ghost word after what is typed into a shell pane; off for a host shell that draws its own. */
    val suggest: Boolean = true,
) {
    /** The hub in use, and its URL; empty until one is added. */
    val hub: Hub? get() = hubs.firstOrNull { it.id == current }
    val hubUrl: String get() = hub?.url ?: ""
}

/** Everything but the token, in plain app-private preferences. */
class SettingsStore(context: Context) {
    companion object {
        const val OPEN_COMMAND_MAX = 200
    }

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
            names = prefs.getBoolean("names", d.names),
            openWith = runCatching { OpenMode.valueOf(prefs.getString("open_with", d.openWith.name)!!) }.getOrDefault(d.openWith),
            openCommand = (prefs.getString("open_command", d.openCommand) ?: "").take(OPEN_COMMAND_MAX),
            suggest = prefs.getBoolean("suggest", d.suggest),
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
            .putBoolean("names", s.names)
            .putString("open_with", s.openWith.name)
            .putString("open_command", s.openCommand.take(OPEN_COMMAND_MAX))
            .putBoolean("suggest", s.suggest)
            .apply()
    }

    /** The last lines sent to a shell, newest first, for the command row. */
    fun recent(): List<String> = prefs.getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }

    fun saveRecent(lines: List<String>) {
        prefs.edit().putString("recent", lines.joinToString("\n")).apply()
    }

    /** The newest talk entry looked at, per hub id, so the messages count survives a restart. */
    fun talkSeen(): Map<String, Long> = prefs.getString("talk_seen", "")!!.split('\n').filter { '=' in it }
        .associate { it.substringBefore('=') to (it.substringAfter('=').toLongOrNull() ?: 0L) }

    fun saveTalkSeen(seen: Map<String, Long>) {
        prefs.edit().putString("talk_seen", seen.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
    }
}
