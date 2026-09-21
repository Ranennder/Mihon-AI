package eu.kanade.tachiyomi.extension.util

import android.app.Activity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionReinstallResultTest {

    @Test
    fun `restoring a pending uninstall waits for its result instead of deleting again`() {
        assertEquals(
            ReinstallOnCreate.WAIT,
            reinstallOnCreate(
                waitingForUninstall = true,
                handedToInstaller = false,
                resultHandled = false,
                mayContinue = true,
            ),
        )
        assertEquals(
            ReinstallOnCreate.FINISH,
            reinstallOnCreate(
                waitingForUninstall = true,
                handedToInstaller = false,
                resultHandled = false,
                mayContinue = false,
            ),
        )
    }

    @Test
    fun `a restored installer handoff cannot launch removal or installation a second time`() {
        assertEquals(
            ReinstallOnCreate.FINISH,
            reinstallOnCreate(
                waitingForUninstall = false,
                handedToInstaller = true,
                resultHandled = true,
                mayContinue = true,
            ),
        )
    }

    @Test
    fun `canceling removal keeps the installed extension and does not launch installation`() {
        assertEquals(
            ReinstallAfterRemoval.CANCEL,
            reinstallAfterRemoval(Activity.RESULT_CANCELED, packageStillInstalled = true, mayContinue = true),
        )
    }

    @Test
    fun `successful result without actual removal is an error`() {
        assertEquals(
            ReinstallAfterRemoval.ERROR,
            reinstallAfterRemoval(Activity.RESULT_OK, packageStillInstalled = true, mayContinue = true),
        )
    }

    @Test
    fun `verified absence permits installation despite a vendor canceled result`() {
        for (result in listOf(Activity.RESULT_OK, Activity.RESULT_CANCELED, Activity.RESULT_FIRST_USER)) {
            assertEquals(
                ReinstallAfterRemoval.INSTALL,
                reinstallAfterRemoval(result, packageStillInstalled = false, mayContinue = true),
            )
        }
    }

    @Test
    fun `late result after cancellation or a replacement attempt never installs`() {
        for (packageStillInstalled in listOf(true, false)) {
            assertEquals(
                ReinstallAfterRemoval.ABANDON,
                reinstallAfterRemoval(Activity.RESULT_OK, packageStillInstalled, mayContinue = false),
            )
        }
    }
}
