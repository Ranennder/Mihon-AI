package eu.kanade.tachiyomi.ui.reader.setting

import android.content.Context
import android.view.Display
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

class ReaderAiTargetWidthTest {

    @Test
    fun `models keep independent arbitrary widths and device based Auto defaults`() {
        val preferences = ReaderPreferences(InMemoryPreferenceStore()) { 1320 }
        assertEquals(1320, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.FAST).targetWidth)
        assertEquals(3960, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.DETAILED).targetWidth)

        preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.FAST, 1733)
        preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.DETAILED, 2819)
        preferences.remoteAiModel.set(ReaderPreferences.RemoteAiModel.DETAILED)
        assertEquals(2819, preferences.remoteAiConfig().targetWidth)
        preferences.remoteAiModel.set(ReaderPreferences.RemoteAiModel.FAST)
        assertEquals(1733, preferences.remoteAiConfig().targetWidth)
        preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.FAST, 0)
        assertEquals(0, preferences.remoteAiConfig().targetWidth)
        assertEquals(2819, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.DETAILED).targetWidth)
    }

    @Test
    fun `numeric entry accepts all integer widths in range and zero`() {
        for (width in listOf(0, 64, 1733, 2049, 16384)) {
            assertEquals(width, ReaderPreferences.parseAiTargetWidth(" $width "))
        }
        for (invalid in listOf("", "-1", "63", "16385", "2048.5", "2x", "1e3", "+2048", "999999999999")) {
            assertNull(ReaderPreferences.parseAiTargetWidth(invalid), invalid)
        }
        val preferences = ReaderPreferences(InMemoryPreferenceStore()) { 1320 }
        assertThrows(IllegalArgumentException::class.java) {
            preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.FAST, 63)
        }
        assertFalse(preferences.remoteAiFastTargetWidth.isSet())
    }

    @Test
    fun `saved model widths are restored independently`() {
        val preferences = ReaderPreferences(
            InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreference("pref_reader_ai_remote_fast_target_width", 1777, -1),
                    InMemoryPreference("pref_reader_ai_remote_detailed_target_width", 3199, -1),
                ),
            ),
        ) { 1320 }
        assertEquals(1777, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.FAST).targetWidth)
        assertEquals(3199, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.DETAILED).targetWidth)
    }

    @Test
    fun `Auto follows each device while a custom width remains independent`() {
        var physicalWidth = 1080
        val preferences = ReaderPreferences(InMemoryPreferenceStore()) { physicalWidth }
        assertEquals(-1, preferences.remoteAiFastTargetWidth.get())
        assertEquals(-1, preferences.remoteAiDetailedTargetWidth.get())
        assertEquals(1080, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.FAST).targetWidth)
        assertEquals(3240, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.DETAILED).targetWidth)
        preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.DETAILED, 2819)
        val captured = preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.FAST)
        physicalWidth = 1440
        assertEquals(1080, captured.targetWidth)
        assertEquals(1440, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.FAST).targetWidth)
        assertEquals(2819, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.DETAILED).targetWidth)
        preferences.setRemoteAiTargetWidth(ReaderPreferences.RemoteAiModel.DETAILED, -1)
        assertEquals(4320, preferences.remoteAiConfig(ReaderPreferences.RemoteAiModel.DETAILED).targetWidth)
        assertThrows(IllegalArgumentException::class.java) {
            ReaderPreferences.RemoteAiConfig(ReaderPreferences.RemoteAiModel.FAST, -1)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `physical portrait width uses display pixels and remains stable after rotation`() {
        val context = mockk<Context>()
        val manager = mockk<WindowManager>()
        val display = mockk<Display>()
        val mode = mockk<Display.Mode>()
        every { context.getSystemService(WindowManager::class.java) } returns manager
        every { manager.defaultDisplay } returns display
        every { display.mode } returns mode
        every { mode.physicalWidth } returns 1320
        every { mode.physicalHeight } returns 2868
        assertEquals(1320, physicalPortraitScreenWidth(context))
        every { mode.physicalWidth } returns 2868
        every { mode.physicalHeight } returns 1320
        assertEquals(1320, physicalPortraitScreenWidth(context))
    }

    @Test
    fun `cache identity includes both model and exact target width`() {
        val fast = ReaderPreferences.RemoteAiConfig(ReaderPreferences.RemoteAiModel.FAST, 1777)
        assertNotEquals(fast.cacheKey, fast.copy(targetWidth = 1778).cacheKey)
        assertNotEquals(fast.cacheKey, fast.copy(model = ReaderPreferences.RemoteAiModel.DETAILED).cacheKey)
        assertNotEquals(fast.cacheKey, fast.copy(targetWidth = 0).cacheKey)
    }
}
