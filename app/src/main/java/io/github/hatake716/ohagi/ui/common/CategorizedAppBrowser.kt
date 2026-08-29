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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppCategory
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/**
 * 通常ドロワーと全アプリピッカーで共有する、iOS App Library 風ブラウザー。
 *
 * - 通常時: 2列の自動カテゴリーカード
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
            val groups = remember(apps) {
                val appsByCategory = apps.groupBy { it.category }
                AppCategory.entries.mapNotNull { category ->
                    appsByCategory[category]
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { category to it }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = 28.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = modifier,
            ) {
                items(
                    items = groups,
                    key = { it.first.name },
                ) { (category, groupedApps) ->
                    val title = appCategoryTitle(category)
                    CategoryCard(
                        title = title,
                        apps = groupedApps,
                        selectedApps = selectedApps,
                        onOpenCategory = { onCategorySelected(category) },
                        onAppClick = onPreviewAppClick,
                    )
                }
            }
        }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(cardShape)
            .background(Color.White.copy(alpha = 0.105f))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), cardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpenCategory,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // iOS App Libraryのカード比率に合わせ、左右端へ押し広げず、
            // 2個の大アイコンを短い固定間隔で中央にまとめる。
            // 幅の狭い端末ではカード内に収まるサイズまで自動的に縮小する。
            val previewIconSize = minOf(
                CategoryPreviewIconSize,
                (maxWidth - CategoryPreviewIconGap) / 2,
            )
            Column {
                PreviewRow(
                    apps = apps.take(2),
                    selectedApps = selectedApps,
                    onAppClick = onAppClick,
                    iconSize = previewIconSize,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        space = CategoryPreviewIconGap,
                        alignment = Alignment.CenterHorizontally,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    apps.getOrNull(2)?.let { app ->
                        CategoryPreviewIcon(
                            app = app,
                            selected = app.ref in selectedApps,
                            onClick = { onAppClick(app) },
                            size = previewIconSize,
                        )
                    } ?: PreviewPlaceholder(previewIconSize)
                    MiniPreviewCluster(
                        title = title,
                        apps = apps.drop(3).take(4),
                        onClick = onOpenCategory,
                        size = previewIconSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(
    apps: List<AppInfo>,
    selectedApps: Set<AppRef>,
    onAppClick: (AppInfo) -> Unit,
    iconSize: Dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(
            space = CategoryPreviewIconGap,
            alignment = Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        repeat(2) { index ->
            apps.getOrNull(index)?.let { app ->
                CategoryPreviewIcon(
                    app = app,
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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
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
        AppIcon(app = app.ref, size = size)
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
    onClick: () -> Unit,
    size: Dp,
) {
    val description = stringResource(R.string.category_open, title)
    val miniIconSize = minOf(
        CategoryMiniPreviewIconSize,
        (size - CategoryMiniPreviewIconGap) / 2,
    )
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
            Column(verticalArrangement = Arrangement.spacedBy(CategoryMiniPreviewIconGap)) {
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(CategoryMiniPreviewIconGap)) {
                        repeat(2) { column ->
                            val app = apps.getOrNull(row * 2 + column)
                            if (app == null) {
                                Spacer(Modifier.size(miniIconSize))
                            } else {
                                AppIcon(app = app.ref, size = miniIconSize)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CategoryPreviewIconSize = 66.dp
private val CategoryPreviewIconGap = 10.dp
private val CategoryMiniPreviewIconSize = 28.dp
private val CategoryMiniPreviewIconGap = 4.dp

internal val IOS_SELECTION_BLUE = Color(0xFF0A84FF)
