package fr.niakimbo.trust.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Petits réglages persistants (SharedPreferences : simple et suffisant ici).
 * Les données de lecture (marque-pages, notes, progression) iront dans Room.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.DARK),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    private val _autoCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CHECK, true))
    val autoCheckFlow: StateFlow<Boolean> = _autoCheck.asStateFlow()

    var autoCheckUpdates: Boolean
        get() = _autoCheck.value
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()
            _autoCheck.value = value
        }

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_AUTO_CHECK = "auto_check_updates"
        const val KEY_LAST_CHECK = "last_update_check"
    }
}
