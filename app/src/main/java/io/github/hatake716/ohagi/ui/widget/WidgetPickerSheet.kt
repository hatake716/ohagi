package io.github.hatake716.ohagi.ui.widget

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.ui.common.IosSearchField
import io.github.hatake716.ohagi.ui.common.IosSheetShape
import io.github.hatake716.ohagi.ui.common.animateIosPressScale
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/** インストール済みAppWidgetProviderを選ぶ、iOS風の下部シート。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetPickerSheet(
    providers: List<AppWidgetProviderInfo>,
    onSelect: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val controller = LocalGraph.current.widgetHost
    var query by remember { mutableStateOf("") }
    val entries = remember(providers, query) {
        val normalized = query.trim()
        providers.map { info ->
            WidgetProviderEntry(
                info = info,
                appLabel = controller.providerAppLabel(info),
                widgetLabel = controller.providerLabel(info),
            )
        }.filter { entry ->
            normalized.isEmpty() ||
                entry.appLabel.contains(normalized, ignoreCase = true) ||
                entry.widgetLabel.contains(normalized, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = IosSheetShape,
        containerColor = Ink.copy(alpha = 0.94f),
        contentColor = Kome,
        scrimColor = Color.Black.copy(alpha = 0.32f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.widget_picker_title),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            IosSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.widget_search_hint),
                clearContentDescription = stringResource(R.string.action_clear_search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (entries.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.widget_no_providers),
                        color = Kome.copy(alpha = 0.62f),
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(
                        items = entries,
                        key = { it.info.provider.flattenToString() },
                    ) { entry ->
                        WidgetProviderRow(
                            entry = entry,
                            onClick = { onSelect(entry.info) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    item { Spacer(Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun WidgetProviderRow(
    entry: WidgetProviderEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember(entry.info.provider) { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateIosPressScale(
        pressed = pressed,
        pressedScale = 0.985f,
        label = "widgetProviderScale",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Icon(
                imageVector = Icons.Rounded.Widgets,
                contentDescription = null,
                tint = Kome,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.widgetLabel,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.appLabel,
                color = Kome.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class WidgetProviderEntry(
    val info: AppWidgetProviderInfo,
    val appLabel: String,
    val widgetLabel: String,
)
