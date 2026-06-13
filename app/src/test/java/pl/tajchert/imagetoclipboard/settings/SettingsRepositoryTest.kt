package pl.tajchert.imagetoclipboard.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFile("test.preferences_pb")
        }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `emits defaults when nothing stored`() = runBlocking {
        val repo = SettingsRepository(newDataStore())

        assertEquals(CopySettings(), repo.settings.first())
    }

    @Test
    fun `update and read round-trip`() = runBlocking {
        val repo = SettingsRepository(newDataStore())
        val wanted = CopySettings(
            format = OutputFormat.JPEG,
            quality = 65,
            maxDimension = MaxDimension.P1080,
        )

        repo.update(wanted)

        assertEquals(wanted, repo.settings.first())
    }

    @Test
    fun `invalid stored enum falls back to defaults`() = runBlocking {
        val dataStore = newDataStore()
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("format")] = "BOGUS"
            prefs[stringPreferencesKey("max_dimension")] = "ALSO_BOGUS"
        }
        val repo = SettingsRepository(dataStore)

        assertEquals(CopySettings(), repo.settings.first())
    }

    @Test
    fun `privacy defaults when nothing stored`() = runBlocking {
        val repo = SettingsRepository(newDataStore())

        assertEquals(PrivacySettings(), repo.privacySettings.first())
    }

    @Test
    fun `privacy update and read round-trip`() = runBlocking {
        val repo = SettingsRepository(newDataStore())
        val wanted = PrivacySettings(historyEnabled = false, autoDelete = AutoDelete.H24)

        repo.updatePrivacy(wanted)

        assertEquals(wanted, repo.privacySettings.first())
    }

    @Test
    fun `invalid stored auto-delete falls back to default`() = runBlocking {
        val dataStore = newDataStore()
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("auto_delete")] = "NEVER_EVER"
        }
        val repo = SettingsRepository(dataStore)

        assertEquals(PrivacySettings(), repo.privacySettings.first())
    }
}
