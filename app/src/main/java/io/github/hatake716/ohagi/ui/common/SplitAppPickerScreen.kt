package io.github.hatake716.ohagi.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppCategory
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.preferredAppRefs
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/** 通知のohagiボタンから開く、2つ目のアプリ専用カテゴリー式ドロワー。 */
@Composable
fun SplitAppPickerScreen(
    firstApp: AppRef,
    onSelectApp: (AppRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val graph = LocalGraph.current
    val apps by graph.appRepository.apps.collectAsStateWithLifecycle()
    val layout by graph.layoutRepository.state.collectAsStateWithLifecycle()
    val rankedLaunches by graph.usageRepository.rankedApps.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<AppCategory?>(null) }
    val availableApps = remember(apps, firstApp.packageName) {
        apps.filter { it.ref.packageName != firstApp.packageName }
    }
    val preferredApps = remember(
        layout.home,
        layout.dock,
        rankedLaunches,
        firstApp.packageName,
    ) {
        layout.preferredAppRefs(rankedLaunches)
            .filterNot { it.packageName == firstApp.packageName }
    }
    val firstLabel = remember(apps, firstApp) {
        apps.firstOrNull { it.ref == firstApp }?.label
            ?: graph.appRepository.labelOf(firstApp)
    }
    val headerTitle = selectedCategory?.let { appCategoryTitle(it) }
        ?: stringResource(R.string.picker_split_second_title)

    BackHandler {
        if (selectedCategory != null) selectedCategory = null else onDismiss()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
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
                    text = headerTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Kome,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.picker_split_first_app, firstLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = Kome.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IosGlassIconButton(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
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

        if (apps.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                CircularProgressIndicator(
                    color = Kome,
                    modifier = Modifier.size(36.dp),
                )
            }
        } else {
            CategorizedAppBrowser(
                apps = availableApps,
                query = query,
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                onPreviewAppClick = { onSelectApp(it.ref) },
                preferredApps = preferredApps,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { app ->
                PickerAppCell(
                    app = app,
                    selected = false,
                    onClick = { onSelectApp(app.ref) },
                )
            }
        }
    }
}
