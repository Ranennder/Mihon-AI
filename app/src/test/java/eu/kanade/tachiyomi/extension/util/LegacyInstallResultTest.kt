package eu.kanade.tachiyomi.extension.util

import android.app.Activity
import eu.kanade.tachiyomi.extension.model.InstallStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacyInstallResultTest {

    @Test
    fun `MIUI early cancellation workaround keeps explicit installer results`() {
        assertTrue(isEmptyLegacyInstallCancellation(Activity.RESULT_CANCELED, null))
        assertFalse(isEmptyLegacyInstallCancellation(Activity.RESULT_CANCELED, -7))
        assertFalse(isEmptyLegacyInstallCancellation(Activity.RESULT_CANCELED, -115))
        assertFalse(isEmptyLegacyInstallCancellation(Activity.RESULT_OK, 1))
        assertFalse(isEmptyLegacyInstallCancellation(Activity.RESULT_OK, null))
        assertFalse(isEmptyLegacyInstallCancellation(Activity.RESULT_FIRST_USER, null))
    }

    @Test
    fun `a rejected APK reports the native cause instead of only a retry state`() {
        val result = parseLegacyInstallResult(Activity.RESULT_FIRST_USER, -7)

        assertEquals(InstallStep.Error, result.step)
        assertTrue(result.errorMessage!!.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertTrue(result.errorMessage.contains("install result -7"))
    }

    @Test
    fun `an explicit native failure is retained when Android reports canceled`() {
        val result = parseLegacyInstallResult(Activity.RESULT_CANCELED, -111)

        assertEquals(InstallStep.Error, result.step)
        assertTrue(result.errorMessage!!.contains("INSTALL_FAILED_USER_RESTRICTED"))
    }

    @Test
    fun `canceling the confirmation does not become an installation error`() {
        for (nativeResult in listOf(null, -115)) {
            val result = parseLegacyInstallResult(Activity.RESULT_CANCELED, nativeResult)

            assertEquals(InstallStep.Idle, result.step)
            assertNull(result.errorMessage)
        }
    }

    @Test
    fun `the native success code is distinct from the activity success code`() {
        val result = parseLegacyInstallResult(Activity.RESULT_OK, 1)

        assertEquals(InstallStep.Installed, result.step)
        assertNull(result.errorMessage)
    }

    @Test
    fun `unknown vendor errors keep the numeric code`() {
        val result = parseLegacyInstallResult(Activity.RESULT_FIRST_USER, -123456)

        assertEquals(InstallStep.Error, result.step)
        assertTrue(result.errorMessage!!.contains("install result -123456"))
    }

    @Test
    fun `a failed result without a native extra remains a visible error`() {
        val result = parseLegacyInstallResult(Activity.RESULT_FIRST_USER, null)

        assertEquals(InstallStep.Error, result.step)
        assertTrue(result.errorMessage!!.contains("install result not provided"))
    }
}
