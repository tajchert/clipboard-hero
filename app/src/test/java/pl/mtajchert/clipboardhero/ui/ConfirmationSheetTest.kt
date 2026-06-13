package pl.mtajchert.clipboardhero.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.mtajchert.clipboardhero.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmationSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private fun str(id: Int) =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun `success shows the copied card`() {
        rule.setContent {
            ClipboardHeroTheme(dynamicColor = false) {
                ConfirmationSheet(CopyState.Success(null, 2_400_000, 480_000), onDone = {})
            }
        }
        rule.onNodeWithText(str(R.string.copied_success)).assertIsDisplayed()
    }

    @Test
    fun `error shows the error card`() {
        rule.setContent {
            ClipboardHeroTheme(dynamicColor = false) {
                ConfirmationSheet(CopyState.Error, onDone = {})
            }
        }
        rule.onNodeWithText(str(R.string.copied_error)).assertIsDisplayed()
    }

    @Test
    fun `silent success draws no card`() {
        rule.setContent {
            ClipboardHeroTheme(dynamicColor = false) {
                ConfirmationSheet(CopyState.SilentSuccess, onDone = {})
            }
        }
        rule.onNodeWithText(str(R.string.copied_success)).assertDoesNotExist()
    }

    @Test
    fun `pending draws no card`() {
        rule.setContent {
            ClipboardHeroTheme(dynamicColor = false) {
                ConfirmationSheet(CopyState.Pending, onDone = {})
            }
        }
        rule.onNodeWithText(str(R.string.copied_success)).assertDoesNotExist()
    }

    @Test
    fun `silent success dismisses immediately`() {
        var done = false
        rule.setContent {
            ConfirmationSheet(CopyState.SilentSuccess, onDone = { done = true })
        }
        rule.waitForIdle()
        assertTrue(done)
    }

    @Test
    fun `success dismisses only after the delay`() {
        rule.mainClock.autoAdvance = false
        var done = false
        rule.setContent {
            ConfirmationSheet(CopyState.Success(null, 0, 0), onDone = { done = true })
        }
        rule.mainClock.advanceTimeBy(1400)
        assertFalse(done)
        rule.mainClock.advanceTimeBy(200)
        assertTrue(done)
    }
}
