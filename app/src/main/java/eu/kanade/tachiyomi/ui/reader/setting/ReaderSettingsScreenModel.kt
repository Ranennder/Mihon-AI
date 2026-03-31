package eu.kanade.tachiyomi.ui.reader.setting

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReaderSettingsScreenModel(
    readerState: StateFlow<ReaderViewModel.State>,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    private val onChangeUpscaleEnabled: (Boolean) -> Unit,
    private val onChangeAiBackend: (ReaderPreferences.AiBackendMode) -> Unit,
    private val onChangeRemoteAiModel: (ReaderPreferences.RemoteAiModel) -> Unit,
    val preferences: ReaderPreferences = Injekt.get(),
) : ScreenModel {

    val viewerFlow = readerState
        .map { it.viewer }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    fun setUpscaleEnabled(enabled: Boolean) {
        onChangeUpscaleEnabled(enabled)
    }

    fun setAiBackendMode(mode: ReaderPreferences.AiBackendMode) {
        onChangeAiBackend(mode)
    }

    fun setRemoteAiModel(mode: ReaderPreferences.RemoteAiModel) {
        onChangeRemoteAiModel(mode)
    }
}
