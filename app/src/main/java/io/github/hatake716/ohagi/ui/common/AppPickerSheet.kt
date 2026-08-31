package io.github.hatake716.ohagi.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppCategory
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/**
 * Dock割り当てとフォルダ追加で共用する自動カテゴリー式ピッカー。
 * 単一選択はタップで即確定し、複数選択はカテゴリーを跨いで選択状態を保持する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    apps: List<AppInfo>,
    frequentApps: List<AppRef> = emptyList(),
    preferredApps: List<AppRef> = emptyList(),
    multiSelect: Boolean,
    onConfirm: (List<AppInfo>) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.picker_title),
    excluded: Set<AppRef> = emptySet(),
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<AppCategory?>(null) }
    val selected = remember { mutableStateListOf<AppInfo>() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val availableApps = remember(apps, excluded) {
        apps.filter { it.ref !in excluded }
    }
    val selectedRefs = selected.mapTo(mutableSetOf()) { it.ref }
    val categoryLabel = selectedCategory?.let { appCategoryTitle(it) }

    fun select(app: AppInfo) {
        if (!multiSelect) {
            onConfirm(listOf(app))
            return
        }
        val existingIndex = selected.indexOfFirst { it.ref == app.ref }
        if (existingIndex >= 0) selected.removeAt(existingIndex) else selected.add(app)
    }

    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = IosSheetShape,
        containerColor = Ink.copy(alpha = 0.90f),
        contentColor = Kome,
        scrimColor = Color.Black.copy(alpha = 0.32f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                if (selectedCategory != null) {
                    IosGlassIconButton(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.category_back),
                        onClick = { selectedCategory = null },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    categoryLabel?.let {
                        Text(
                            text = it,
                            color = Kome.copy(alpha = 0.62f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (multiSelect) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = IOS_SELECTION_BLUE,
                        ),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        enabled = selected.isNotEmpty(),
                        onClick = { onConfirm(selected.toList()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IOS_SELECTION_BLUE,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(
                            text = if (selected.isEmpty()) {
                                stringResource(R.string.action_add)
                            } else {
                                stringResource(R.string.action_add_count, selected.size)
                            },
                        )
                    }
                }
            }

            IosSearchField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.isNotBlank()) selectedCategory = null
                },
                placeholder = stringResource(R.string.search_apps_hint),
                clearContentDescription = stringResource(R.string.action_clear_search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )

            CategorizedAppBrowser(
                apps = availableApps,
                query = query,
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                onPreviewAppClick = { app, _ -> select(app) },
                frequentApps = frequentApps,
                preferredApps = preferredApps,
                selectedApps = selectedRefs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { app ->
                PickerAppCell(
                    app = app,
                    selected = app.ref in selectedRefs,
                    onClick = { select(app) },
                )
            }
        }
    }
}

@Composable
internal fun PickerAppCell(
    app: AppInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember(app.ref) { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateIosPressScale(
        pressed = pressed,
        label = "pickerCategoryCellScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Box {
            AppIcon(app = app.ref, size = 56.dp)
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.app_selected, app.label),
                    tint = IOS_SELECTION_BLUE,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Ink),
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodySmall,
            color = Kome,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
