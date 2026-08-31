package io.github.hatake716.ohagi.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppCategory
import io.github.hatake716.ohagi.data.AppIconRequest
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/**
 * 通常ドロワーと全アプリピッカーで共有する、iOS App Library 風ブラウザー。
 *
 * - 通常時: よく使うアプリの独立カード + 2列の自動カテゴリーカード
 * - カテゴリー選択時: そのカテゴリーだけの4列グリッド
 * - 検索時: 全カテゴリー横断の4列グリッド
 */
@Composable
fun CategorizedAppBrowser(
    apps: List<AppInfo>,
    query: String,
    selectedCategory: AppCategory?,
    onCategorySelected: (AppCategory?) -> Unit,
    onPreviewAppClick: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
    frequentApps: List<AppRef> = emptyList(),
    preferredApps: List<AppRef> = emptyList(),
    selectedApps: Set<AppRef> = emptySet(),
    appCell: @Composable (AppInfo) -> Unit,
) {
    val matchingApps = remember(apps, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(normalized, ignoreCase = true) ||
                    app.ref.packageName.contains(normalized, ignoreCase = true)
            }
        }
    }

    val categoryApps = remember(apps, selectedCategory) {
        if (selectedCategory == null) emptyList()
        else apps.filter { it.category == selectedCategory }
    }

    when {
        query.isNotBlank() -> AppGrid(
            apps = matchingApps,
            modifier = modifier,
            appCell = appCell,
        )

        selectedCategory != null -> AppGrid(
            apps = categoryApps,
            modifier = modifier,
            appCell = appCell,
        )

        else -> {
            val overview = remember(apps, frequentApps, preferredApps) {
                buildAppBrowserOverviewContent(
                    apps = apps,
                    frequentAppRefs = frequentApps,
                    preferredApps = preferredApps,
                )
            }
            // カードごとのBoxWithConstraintsは、Pagerへ入る最初のフレームで
            // 可視カード数だけSubcomposeを発生させる。グリッド幅から1回だけ
            // 実寸を計算し、全カードへ共有する。
            BoxWithConstraints(modifier = modifier) {
                val cardWidth = (
                    maxWidth -
                        CATEGORY_GRID_HORIZONTAL_PADDING * 2 -
                        CATEGORY_GRID_GAP
                    ) / 2
                val previewIconSize = minOf(
                    APP_LIBRARY_PREVIEW_ICON_SIZE,
                    (
                        cardWidth -
                            CATEGORY_CARD_HORIZONTAL_PADDING * 2 -
                            APP_LIBRARY_PREVIEW_ICON_GAP
                        ) / 2,
                ).coerceAtLeast(1.dp)

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = CATEGORY_GRID_HORIZONTAL_PADDING,
                        end = CATEGORY_GRID_HORIZONTAL_PADDING,
                        top = 8.dp,
                        bottom = 28.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(CATEGORY_GRID_GAP),
                    verticalArrangement = Arrangement.spacedBy(CATEGORY_GRID_GAP),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (overview.frequentApps.isNotEmpty()) {
                        item(
                            key = "frequent_apps",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "frequent_apps",
                        ) {
                            FrequentAppsCard(
                                apps = overview.frequentApps,
                                selectedApps = selectedApps,
                                onAppClick = onPreviewAppClick,
                            )
                        }
                    }
                    items(
                        items = overview.categoryGroups,
                        key = { it.first.name },
                        contentType = { "category" },
                    ) { (category, groupedApps) ->
                        val title = appCategoryTitle(category)
                        CategoryCard(
                            title = title,
                            apps = groupedApps,
                            selectedApps = selectedApps,
                            previewIconSize = previewIconSize,
                            onOpenCategory = { onCategorySelected(category) },
                            onAppClick = onPreviewAppClick,
                        )
                    }
                }
            }
        }
    }
}

/** 履歴上位を通常カテゴリーから独立させ、必ず見出し付きで表示する。 */
@Composable
private fun FrequentAppsCard(
    apps: List<AppInfo>,
    selectedApps: Set<AppRef>,
    onAppClick: (AppInfo) -> Unit,
) {
    val visibleApps = remember(apps) { apps.take(FREQUENT_APP_LIMIT) }
    val appRows = remember(visibleApps) { visibleApps.chunked(FREQUENT_APP_COLUMNS) }
    val density = LocalDensity.current
    val iconRequests = remember(visibleApps, density.density) {
        val iconSizePx = with(density) { FREQUENT_APP_ICON_SIZE.roundToPx() }
        visibleApps.map { app -> AppIconRequest(app.ref, iconSizePx) }
    }
    val icons by rememberRequestedAppIconBitmaps(iconRequests)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.105f))
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.frequent_apps_title),
            color = Kome,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        appRows.forEachIndexed { rowIndex, rowApps ->
            if (rowIndex > 0) Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(FREQUENT_APP_COLUMNS) { column ->
                    val index = rowIndex * FREQUENT_APP_COLUMNS + column
                    val app = rowApps.getOrNull(column)
                    if (app == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        FrequentAppCell(
                            app = app,
                            icon = icons.getOrNull(index),
                            selected = app.ref in selectedApps,
                            onClick = { onAppClick(app) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrequentAppCell(
    app: AppInfo,
    icon: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember(app.ref) { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateIosPressScale(
        pressed = pressed,
        label = "frequentAppScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .semantics {
                contentDescription = app.label
                role = Role.Button
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(FREQUENT_APP_ICON_SIZE)
                .drawWithContent {
                    scale(scaleX = scale, scaleY = scale) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            AppIconImage(
                icon = icon,
                size = FREQUENT_APP_ICON_SIZE,
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = IOS_SELECTION_BLUE,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Ink),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = app.label,
            color = Kome,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

fun appCategoryTitleRes(category: AppCategory): Int =
    when (category) {
        AppCategory.SOCIAL -> R.string.category_social
        AppCategory.PRODUCTIVITY_FINANCE -> R.string.category_productivity_finance
        AppCategory.PHOTO_VIDEO -> R.string.category_photo_video
        AppCategory.ENTERTAINMENT -> R.string.category_entertainment
        AppCategory.GAMES -> R.string.category_games
        AppCategory.NEWS_READING -> R.string.category_news_reading
        AppCategory.TRAVEL_WEATHER -> R.string.category_travel_weather
        AppCategory.SHOPPING_FOOD -> R.string.category_shopping_food
        AppCategory.HEALTH_FITNESS -> R.string.category_health_fitness
        AppCategory.UTILITIES -> R.string.category_utilities
        AppCategory.OTHER -> R.string.category_other
    }

@Composable
fun appCategoryTitle(category: AppCategory): String =
    stringResource(appCategoryTitleRes(category))

@Composable
private fun AppGrid(
    apps: List<AppInfo>,
    modifier: Modifier,
    appCell: @Composable (AppInfo) -> Unit,
) {
    if (apps.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = Icons.Rounded.SearchOff,
                contentDescription = stringResource(R.string.category_no_apps),
                tint = Kome.copy(alpha = 0.45f),
                modifier = Modifier.size(48.dp),
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 8.dp,
            bottom = 28.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(
            items = apps,
            key = { "${it.ref.packageName}/${it.ref.className}" },
        ) { app ->
            appCell(app)
        }
    }
}

@Composable
private fun CategoryCard(
    title: String,
    apps: List<AppInfo>,
    selectedApps: Set<AppRef>,
    previewIconSize: Dp,
    onOpenCategory: () -> Unit,
    onAppClick: (AppInfo) -> Unit,
) {
    val cardShape = RoundedCornerShape(24.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateIosPressScale(
        pressed = pressed,
        pressedScale = 0.975f,
        label = "appCategoryCardScale",
    )
    val miniIconSize = minOf(
        APP_LIBRARY_MINI_ICON_SIZE,
        (previewIconSize - APP_LIBRARY_MINI_ICON_GAP) / 2,
    )
    val density = LocalDensity.current
    val iconRequests = remember(
        apps,
        previewIconSize,
        miniIconSize,
        density.density,
    ) {
        val previewSizePx = with(density) { previewIconSize.roundToPx() }
        val miniSizePx = with(density) { miniIconSize.roundToPx() }
        buildList {
            apps.take(3).forEach { app ->
                add(AppIconRequest(app.ref, previewSizePx))
            }
            apps.drop(3).take(4).forEach { app ->
                add(AppIconRequest(app.ref, miniSizePx))
            }
        }
    }
    val previewIcons by rememberRequestedAppIconBitmaps(iconRequests)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .drawWithContent {
                scale(scaleX = scale, scaleY = scale) { this@drawWithContent.drawContent() }
            }
            .clip(cardShape)
            .background(Color.White.copy(alpha = 0.105f))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), cardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpenCategory,
            )
            .padding(horizontal = CATEGORY_CARD_HORIZONTAL_PADDING, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                color = Kome,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Column {
            PreviewRow(
                apps = apps.take(2),
                icons = previewIcons.take(2),
                selectedApps = selectedApps,
                onAppClick = onAppClick,
                iconSize = previewIconSize,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    space = APP_LIBRARY_PREVIEW_ICON_GAP,
                    alignment = Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                apps.getOrNull(2)?.let { app ->
                    CategoryPreviewIcon(
                        app = app,
                        icon = previewIcons.getOrNull(2),
                        selected = app.ref in selectedApps,
                        onClick = { onAppClick(app) },
                        size = previewIconSize,
                    )
                } ?: PreviewPlaceholder(previewIconSize)
                MiniPreviewCluster(
                    title = title,
                    apps = apps.drop(3).take(4),
                    icons = previewIcons.drop(3),
                    onClick = onOpenCategory,
                    size = previewIconSize,
                    miniIconSize = miniIconSize,
                )
            }
        }
    }
}

@Composable
private fun PreviewRow(
    apps: List<AppInfo>,
    icons: List<ImageBitmap?>,
    selectedApps: Set<AppRef>,
    onAppClick: (AppInfo) -> Unit,
    iconSize: Dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(
            space = APP_LIBRARY_PREVIEW_ICON_GAP,
            alignment = Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        repeat(2) { index ->
            apps.getOrNull(index)?.let { app ->
                CategoryPreviewIcon(
                    app = app,
                    icon = icons.getOrNull(index),
                    selected = app.ref in selectedApps,
                    onClick = { onAppClick(app) },
                    size = iconSize,
                )
            } ?: PreviewPlaceholder(iconSize)
        }
    }
}

@Composable
private fun CategoryPreviewIcon(
    app: AppInfo,
    icon: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    size: Dp,
) {
    val interactionSource = remember(app.ref) { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateIosPressScale(
        pressed = pressed,
        label = "categoryPreviewIconScale",
    )
    Box(
        modifier = Modifier
            .size(size)
            .drawWithContent {
                scale(scaleX = scale, scaleY = scale) { this@drawWithContent.drawContent() }
            }
            .semantics {
                contentDescription = app.label
                role = Role.Button
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        AppIconImage(icon = icon, size = size, decorated = false)
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = IOS_SELECTION_BLUE,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Ink),
            )
        }
    }
}

@Composable
private fun PreviewPlaceholder(size: Dp) {
    Spacer(Modifier.size(size))
}

@Composable
private fun MiniPreviewCluster(
    title: String,
    apps: List<AppInfo>,
    icons: List<ImageBitmap?>,
    onClick: () -> Unit,
    size: Dp,
    miniIconSize: Dp,
) {
    val description = stringResource(R.string.category_open, title)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.16f))
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        if (apps.isEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Apps,
                contentDescription = null,
                tint = Kome.copy(alpha = 0.70f),
                modifier = Modifier.size(28.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(APP_LIBRARY_MINI_ICON_GAP)) {
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(APP_LIBRARY_MINI_ICON_GAP)) {
                        repeat(2) { column ->
                            val app = apps.getOrNull(row * 2 + column)
                            if (app == null) {
                                Spacer(Modifier.size(miniIconSize))
                            } else {
                                AppIconImage(
                                    icon = icons.getOrNull(row * 2 + column),
                                    size = miniIconSize,
                                    decorated = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val APP_LIBRARY_PREVIEW_ICON_SIZE = 66.dp
internal val FREQUENT_APP_ICON_SIZE = 56.dp
private val APP_LIBRARY_PREVIEW_ICON_GAP = 10.dp
internal val APP_LIBRARY_MINI_ICON_SIZE = 28.dp
private val APP_LIBRARY_MINI_ICON_GAP = 4.dp
private val CATEGORY_GRID_HORIZONTAL_PADDING = 12.dp
private val CATEGORY_GRID_GAP = 12.dp
private val CATEGORY_CARD_HORIZONTAL_PADDING = 12.dp
private const val FREQUENT_APP_COLUMNS = 4

internal val IOS_SELECTION_BLUE = Color(0xFF0A84FF)
