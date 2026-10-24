package app.muxway.android.store

import android.content.Context

enum class Theme { GREEN, AMBER, PAPER }

data class Settings(
    val hubUrl: String = "",
    val refreshSeconds: Int = 2,
    val lines: Int = 200,
    val textSp: Int = 12,
    val theme: Theme = Theme.GREEN,
    val blockScreenshots: Boolean = false,
    val hardwareKeyboard: Boolean = false,
    val effects: Boolean = true,
)

/** Everything but the token, in plain app-private preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            hubUrl = prefs.getString("hub_url", d.hubUrl) ?: d.hubUrl,
            refreshSeconds = prefs.getInt("refresh", d.refreshSeconds).coerceIn(1, 60),
            lines = prefs.getInt("lines", d.lines).coerceIn(10, 2000),
            textSp = prefs.getInt("text_sp", d.textSp).coerceIn(6, 24),
            theme = runCatching { Theme.valueOf(prefs.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            blockScreenshots = prefs.getBoolean("block_screenshots", d.blockScreenshots),
            hardwareKeyboard = prefs.getBoolean("hardware_keyboard", d.hardwareKeyboard),
            effects = prefs.getBoolean("effects", d.effects),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("hub_url", s.hubUrl)
            .putInt("refresh", s.refreshSeconds)
            .putInt("lines", s.lines)
            .putInt("text_sp", s.textSp)
            .putString("theme", s.theme.name)
            .putBoolean("block_screenshots", s.blockScreenshots)
            .putBoolean("hardware_keyboard", s.hardwareKeyboard)
            .putBoolean("effects", s.effects)
            .apply()
    }
}
