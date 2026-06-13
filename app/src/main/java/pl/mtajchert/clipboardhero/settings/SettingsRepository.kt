package pl.mtajchert.clipboardhero.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<CopySettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val defaults = CopySettings()
            CopySettings(
                format = prefs[KEY_FORMAT].toEnumOrNull<OutputFormat>() ?: defaults.format,
                quality = prefs[KEY_QUALITY]?.coerceIn(50, 100) ?: defaults.quality,
                maxDimension = prefs[KEY_MAX_DIMENSION].toEnumOrNull<MaxDimension>() ?: defaults.maxDimension,
            )
        }

    suspend fun update(settings: CopySettings) {
        dataStore.edit { prefs ->
            prefs[KEY_FORMAT] = settings.format.name
            prefs[KEY_QUALITY] = settings.quality
            prefs[KEY_MAX_DIMENSION] = settings.maxDimension.name
        }
    }

    val privacySettings: Flow<PrivacySettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val defaults = PrivacySettings()
            PrivacySettings(
                historyEnabled = prefs[KEY_HISTORY_ENABLED] ?: defaults.historyEnabled,
                autoDelete = prefs[KEY_AUTO_DELETE].toEnumOrNull<AutoDelete>() ?: defaults.autoDelete,
            )
        }

    suspend fun updatePrivacy(settings: PrivacySettings) {
        dataStore.edit { prefs ->
            prefs[KEY_HISTORY_ENABLED] = settings.historyEnabled
            prefs[KEY_AUTO_DELETE] = settings.autoDelete.name
        }
    }

    companion object {
        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.settingsDataStore)

        private val KEY_FORMAT = stringPreferencesKey("format")
        private val KEY_QUALITY = intPreferencesKey("quality")
        private val KEY_MAX_DIMENSION = stringPreferencesKey("max_dimension")
        private val KEY_HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        private val KEY_AUTO_DELETE = stringPreferencesKey("auto_delete")

        private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
            this?.let { name -> enumValues<T>().firstOrNull { it.name == name } }
    }
}
