package eu.kanade.presentation.reader.settings

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.upscale.RemoteAiServerDiscovery
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun rememberRemoteAiInternetStatus(preferences: ReaderPreferences): String? {
    val context = LocalContext.current
    val enabled by preferences.remoteAiInternetAccess.collectAsState()
    val manualUrl by preferences.remoteAiBaseUrl.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled, manualUrl) {
        if (!enabled) {
            status = null
            return@LaunchedEffect
        }
        val savedUrl = preferences.remoteAiInternetBaseUrl.get()
        if (!context.isConnectedToWifi()) {
            status = if (savedUrl.toHttpUrlOrNull()?.isHttps == true) {
                context.stringResource(MR.strings.reader_ai_internet_saved, savedUrl)
            } else {
                context.stringResource(MR.strings.pref_reader_ai_internet_access_summary)
            }
            return@LaunchedEffect
        }
        status = context.stringResource(MR.strings.reader_ai_internet_pairing)
        status = try {
            val url = withContext(Dispatchers.IO) {
                RemoteAiServerDiscovery(context.applicationContext as Application, preferences, Injekt.get())
                    .pairInternetAccess()
            }
            context.stringResource(MR.strings.reader_ai_internet_saved, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.stringResource(MR.strings.reader_ai_internet_pairing_failed, e.message.orEmpty())
        }
    }
    return status
}
