package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun AiTargetWidthSettings(
    preferences: ReaderPreferences,
    enabled: Boolean = true,
    onWidthChanged: (ReaderPreferences.RemoteAiModel, Int) -> Unit = preferences::setRemoteAiTargetWidth,
) {
    var editingModel by remember { mutableStateOf<ReaderPreferences.RemoteAiModel?>(null) }
    var draft by remember { mutableStateOf("") }
    val nativeLabel = stringResource(MR.strings.reader_ai_target_width_native)

    Column(modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal)) {
        Text(stringResource(MR.strings.pref_reader_ai_target_width), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(MR.strings.pref_reader_ai_target_width_summary),
            style = MaterialTheme.typography.bodySmall,
        )
        ReaderPreferences.RemoteAiModel.entries.forEach { model ->
            val storedWidth by preferences.remoteAiTargetWidth(model).collectAsState()
            val width = storedWidth.takeIf { ReaderPreferences.isValidAiTargetWidth(it) } ?: model.defaultTargetWidth
            val value = when (width) {
                ReaderPreferences.AI_TARGET_WIDTH_AUTO -> stringResource(
                    MR.strings.reader_ai_target_width_auto,
                    preferences.remoteAiAutoTargetWidth(model),
                )
                0 -> nativeLabel
                else -> stringResource(MR.strings.reader_ai_target_width_pixels, width)
            }
            TextButton(
                enabled = enabled,
                onClick = {
                    draft = if (width == ReaderPreferences.AI_TARGET_WIDTH_AUTO) {
                        preferences.remoteAiAutoTargetWidth(model).toString()
                    } else {
                        width.toString()
                    }
                    editingModel = model
                },
            ) {
                Text("${stringResource(model.titleRes)}: $value")
            }
        }
    }

    editingModel?.let { model ->
        val parsedWidth = ReaderPreferences.parseAiTargetWidth(draft)
        AlertDialog(
            onDismissRequest = { editingModel = null },
            title = {
                Text("${stringResource(model.titleRes)}: ${stringResource(MR.strings.pref_reader_ai_target_width)}")
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(MR.strings.reader_ai_target_width_unit)) },
                        isError = parsedWidth == null,
                        supportingText = { Text(stringResource(MR.strings.reader_ai_target_width_range)) },
                    )
                    TextButton(onClick = { draft = "0" }) { Text(nativeLabel) }
                    TextButton(
                        onClick = {
                            onWidthChanged(model, ReaderPreferences.AI_TARGET_WIDTH_AUTO)
                            editingModel = null
                        },
                    ) {
                        Text(
                            stringResource(
                                MR.strings.reader_ai_target_width_auto,
                                preferences.remoteAiAutoTargetWidth(model),
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = parsedWidth != null,
                    onClick = {
                        parsedWidth?.let { onWidthChanged(model, it) }
                        editingModel = null
                    },
                ) { Text(stringResource(MR.strings.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingModel = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
