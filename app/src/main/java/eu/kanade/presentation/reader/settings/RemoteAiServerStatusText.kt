package eu.kanade.presentation.reader.settings

import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun remoteAiServerStatusText(
    manualUrl: String,
    discoveredUrl: String,
): String? {
    return when {
        discoveredUrl.isNotBlank() && manualUrl.isBlank() -> {
            stringResource(MR.strings.reader_ai_remote_url_auto_active, discoveredUrl)
        }
        discoveredUrl.isNotBlank() -> {
            stringResource(MR.strings.reader_ai_remote_url_auto_discovered, discoveredUrl)
        }
        manualUrl.isBlank() -> {
            stringResource(MR.strings.reader_ai_remote_url_auto_hint)
        }
        isLikelyTailscaleUrl(manualUrl) -> {
            stringResource(MR.strings.reader_ai_remote_url_tailscale_active, manualUrl)
        }
        else -> null
    }
}

@Composable
internal fun remoteAiTokenSubtitle(): String {
    return stringResource(MR.strings.reader_ai_remote_token_hint)
}

internal fun remoteAiServerUrlPreferenceSubtitle(
    manualUrl: String,
    statusText: String?,
): String? {
    return when {
        manualUrl.isNotBlank() && !statusText.isNullOrBlank() -> "%s\n$statusText"
        manualUrl.isNotBlank() -> "%s"
        else -> statusText
    }
}

private fun isLikelyTailscaleUrl(value: String): Boolean {
    val trimmed = value.trim().lowercase()
    return ".ts.net" in trimmed ||
        ".beta.tailscale.net" in trimmed ||
        trimmed.contains("://100.") ||
        trimmed.startsWith("100.")
}
