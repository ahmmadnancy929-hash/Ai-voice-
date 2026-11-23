package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.i18n.AppLanguage
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.studioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vocal_studio_prefs"
)

enum class ProcessingEngineMode {
    ON_DEVICE_DSP,
    REMOTE_DEMUCS
}

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val engineMode: ProcessingEngineMode = ProcessingEngineMode.ON_DEVICE_DSP,
    val remoteServerUrl: String = "http://10.0.2.2:8000",
    val isolationStrength: Float = 0.65f,
    val lastPendingUri: String? = null,
    val lastPendingTitle: String? = null
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val ENGINE_MODE = stringPreferencesKey("engine_mode")
        val REMOTE_SERVER_URL = stringPreferencesKey("remote_server_url")
        val ISOLATION_STRENGTH = floatPreferencesKey("isolation_strength")
        val LAST_PENDING_URI = stringPreferencesKey("last_pending_uri")
        val LAST_PENDING_TITLE = stringPreferencesKey("last_pending_title")
    }

    val preferencesFlow: Flow<UserPreferences> = context.studioDataStore.data.map { prefs ->
        val theme = prefs[Keys.THEME_MODE]?.let {
            runCatching { ThemeMode.valueOf(it) }.getOrNull()
        } ?: ThemeMode.DARK

        val lang = prefs[Keys.LANGUAGE]?.let {
            runCatching { AppLanguage.valueOf(it) }.getOrNull()
        } ?: AppLanguage.ENGLISH

        val engine = prefs[Keys.ENGINE_MODE]?.let {
            runCatching { ProcessingEngineMode.valueOf(it) }.getOrNull()
        } ?: ProcessingEngineMode.ON_DEVICE_DSP

        UserPreferences(
            themeMode = theme,
            language = lang,
            engineMode = engine,
            remoteServerUrl = prefs[Keys.REMOTE_SERVER_URL] ?: "http://10.0.2.2:8000",
            isolationStrength = prefs[Keys.ISOLATION_STRENGTH] ?: 0.65f,
            lastPendingUri = prefs[Keys.LAST_PENDING_URI],
            lastPendingTitle = prefs[Keys.LAST_PENDING_TITLE]
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.studioDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.studioDataStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    suspend fun setEngineMode(mode: ProcessingEngineMode) {
        context.studioDataStore.edit { it[Keys.ENGINE_MODE] = mode.name }
    }

    suspend fun setRemoteServerUrl(url: String) {
        context.studioDataStore.edit { it[Keys.REMOTE_SERVER_URL] = url.trim() }
    }

    suspend fun setIsolationStrength(strength: Float) {
        context.studioDataStore.edit { it[Keys.ISOLATION_STRENGTH] = strength.coerceIn(0.15f, 1.0f) }
    }

    suspend fun savePendingJob(uriString: String?, title: String?) {
        context.studioDataStore.edit { prefs ->
            if (uriString == null) {
                prefs.remove(Keys.LAST_PENDING_URI)
                prefs.remove(Keys.LAST_PENDING_TITLE)
            } else {
                prefs[Keys.LAST_PENDING_URI] = uriString
                prefs[Keys.LAST_PENDING_TITLE] = title ?: "Audio Track"
            }
        }
    }
}
