package pl.mtajchert.clipboardhero

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pl.mtajchert.clipboardhero.settings.CopySettings
import pl.mtajchert.clipboardhero.settings.MaxDimension
import pl.mtajchert.clipboardhero.settings.OutputFormat
import pl.mtajchert.clipboardhero.settings.SettingsRepository
import pl.mtajchert.clipboardhero.ui.CopyState
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    // DataStore runs on the same test dispatcher, so advanceUntilIdle() drains its IO too.
    private val dataStoreScope = CoroutineScope(dispatcher + Job())
    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var repo: ImageClipboardRepository
    private var uriCounter = 0
    private val now = 1_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // FileProvider caches path strategy per authority; Robolectric rotates filesDir per test.
        FileProvider::class.java.getDeclaredField("sCache").apply {
            isAccessible = true
            (get(null) as MutableMap<*, *>).clear()
        }
        context = ApplicationProvider.getApplicationContext()
        val ds: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                tmp.newFile("settings.preferences_pb")
            }
        settingsRepo = SettingsRepository(ds)
        repo = ImageClipboardRepository(context, clock = { now })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dataStoreScope.cancel()
    }

    private fun newViewModel() = ShareViewModel(repo, settingsRepo, context, dispatcher)

    private fun registerSource(): Uri {
        val uri = Uri.parse("content://test.source/img${uriCounter++}")
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        return uri
    }

    // ORIGINAL format = byte-exact pass-through, so the copy succeeds without decoding.
    private val passthrough = CopySettings(
        format = OutputFormat.ORIGINAL,
        maxDimension = MaxDimension.ORIGINAL,
    )

    @Test
    fun `toggle on emits Success`() = runTest(dispatcher) {
        settingsRepo.update(passthrough.copy(showConfirmation = true))
        advanceUntilIdle()
        val vm = newViewModel()

        vm.copy(registerSource(), "image/png")
        advanceUntilIdle()

        assertTrue(vm.copyState.value is CopyState.Success)
    }

    @Test
    fun `toggle off emits SilentSuccess`() = runTest(dispatcher) {
        settingsRepo.update(passthrough.copy(showConfirmation = false))
        advanceUntilIdle()
        val vm = newViewModel()

        vm.copy(registerSource(), "image/png")
        advanceUntilIdle()

        assertEquals(CopyState.SilentSuccess, vm.copyState.value)
    }

    @Test
    fun `unreadable uri emits Error even when toggle off`() = runTest(dispatcher) {
        settingsRepo.update(passthrough.copy(showConfirmation = false))
        advanceUntilIdle()
        val vm = newViewModel()

        vm.copy(Uri.parse("content://nope/missing"), "image/png")
        advanceUntilIdle()

        assertEquals(CopyState.Error, vm.copyState.value)
    }

    @Test
    fun `null source emits Error`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.copy(null, "image/png")
        advanceUntilIdle()

        assertEquals(CopyState.Error, vm.copyState.value)
    }

    @Test
    fun `our own fileprovider authority is rejected`() = runTest(dispatcher) {
        val vm = newViewModel()
        val ownUri = Uri.parse("content://${ImageClipboardRepository.AUTHORITY}/clips/clip_1.png")

        vm.copy(ownUri, "image/png")
        advanceUntilIdle()

        assertEquals(CopyState.Error, vm.copyState.value)
    }

    @Test
    fun `copy is idempotent across repeat calls`() = runTest(dispatcher) {
        settingsRepo.update(passthrough.copy(showConfirmation = true))
        advanceUntilIdle()
        val vm = newViewModel()

        vm.copy(registerSource(), "image/png")
        vm.copy(registerSource(), "image/png") // guarded: must be a no-op
        advanceUntilIdle()

        assertTrue(vm.copyState.value is CopyState.Success)
        assertEquals(1, repo.history(RetentionPolicy(maxItems = 10, ttlMillis = null)).size)
    }
}
