package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderTopBar(
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    aiEnabled: Boolean,
    onToggleAi: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenReaderSettings: (() -> Unit)?,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        title = mangaTitle,
        subtitle = chapterTitle,
        navigateUp = navigateUp,
        actions = {
            TextButton(
                onClick = onToggleAi,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                Text(
                    text = stringResource(MR.strings.action_ai),
                    color = if (aiEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (aiEnabled) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        add(
                            AppBar.Action(
                                title = stringResource(
                                    if (bookmarked) {
                                        MR.strings.action_remove_bookmark
                                    } else {
                                        MR.strings.action_bookmark
                                    },
                                ),
                                icon = if (bookmarked) {
                                    Icons.Outlined.Bookmark
                                } else {
                                    Icons.Outlined.BookmarkBorder
                                },
                                onClick = onToggleBookmarked,
                            ),
                        )
                        onOpenReaderSettings?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_settings),
                                    onClick = it,
                                ),
                            )
                        }
                        onOpenInWebView?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_open_in_web_view),
                                    onClick = it,
                                ),
                            )
                        }
                        onOpenInBrowser?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_open_in_browser),
                                    onClick = it,
                                ),
                            )
                        }
                        onShare?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_share),
                                    onClick = it,
                                ),
                            )
                        }
                    }
                    .build(),
            )
        },
    )
}
