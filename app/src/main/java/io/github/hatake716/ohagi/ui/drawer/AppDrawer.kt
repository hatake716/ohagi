package io.github.hatake716.ohagi.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dock
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppCategory
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.common.AppIconImage
import io.github.hatake716.ohagi.ui.common.CategorizedAppBrowser
import io.github.hatake716.ohagi.ui.common.IosGlassIconButton
import io.github.hatake716.ohagi.ui.common.IosMoreButton
import io.github.hatake716.ohagi.ui.common.IosSearchField
import io.github.hatake716.ohagi.ui.common.MenuEntry
import io.github.hatake716.ohagi.ui.common.MenuSheet
import io.github.hatake716.ohagi.ui.common.animateIosPressScale
import io.github.hatake716.ohagi.ui.common.appCategoryTitle
import io.github.hatake716.ohagi.ui.common.rememberAppIconBitmap
import io.github.hatake716.ohagi.ui.dragdrop.DragPayload
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDragSource
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/**
 * アプリドロワー(全画面オーバーレイ)。
 * ホスト側の AnimatedVisibility で出し入れされる。
 * 自動カテゴリーからアプリを選び、カテゴリー内ではタップ起動・長押しD&D・メニュー操作を行う。
 */
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    onAddToWorkspace: (AppInfo) -> Unit,
    onAddToDock: (AppInfo) -> Unit,
    onAppInfo: (AppInfo) -> Unit,
    onUninstall: (AppInfo) -> Unit,
    onOpenDefaultHome: () -> Unit,
    /** 公式D&D開始後にドロワーを閉じ、背後のdrop targetを露出する。 */
    onDragStarted: (DragPayload) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var menuTarget by remember { mutableStateOf<AppInfo?>(null) }
    var selectedCategory by remember { mutableStateOf<AppCategory?>(null) }

    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }
    val headerTitle = selectedCategory?.let { appCategoryTitle(it) }
        ?: stringResource(R.string.drawer_title)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.64f))
            // ステータスバー/ナビバー/IME/ディスプレイカットアウトすべてを避ける
            .safeDrawingPadding()
    ) {
        // 上部バー: タイトル + 閉じるボタン
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
        ) {
            if (selectedCategory != null) {
                IosGlassIconButton(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.category_back),
                    onClick = { selectedCategory = null },
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = headerTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Kome,
                modifier = Modifier.weight(1f),
            )
            // デフォルトホーム設定(ランチャーとして必須の導線)
            IosGlassIconButton(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.menu_set_default_home),
                onClick = onOpenDefaultHome,
            )
            Spacer(Modifier.width(8.dp))
            IosGlassIconButton(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
        }

        // 検索フィールド
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
            apps = apps,
            query = query,
            selectedCategory = selectedCategory,
            onCategorySelected = { selectedCategory = it },
            onPreviewAppClick = onLaunch,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { app ->
            DrawerCell(
                app = app,
                onTap = { onLaunch(app) },
                onMenu = { menuTarget = app },
                onDragStarted = onDragStarted,
            )
        }
    }

    // セル右上の「その他」ボタンから開くメニュー
    menuTarget?.let { app ->
        MenuSheet(
            entries = listOf(
                MenuEntry(stringResource(R.string.drawer_add_to_workspace), Icons.Rounded.Add) {
                    onAddToWorkspace(app)
                },
                MenuEntry(stringResource(R.string.drawer_add_to_dock), Icons.Rounded.Dock) {
                    onAddToDock(app)
                },
                MenuEntry(stringResource(R.string.action_app_info), Icons.Rounded.Info) {
                    onAppInfo(app)
                },
                MenuEntry(
                    stringResource(R.string.action_uninstall),
                    Icons.Rounded.Delete,
                    destructive = true,
                ) {
                    onUninstall(app)
                },
            ),
            onDismiss = { menuTarget = null },
            header = { DrawerMenuHeader(app) },
        )
    }
}

/** ドロワーの1セル。長押しは公式D&D、メニューは右上ボタンに分離する。 */
@Composable
private fun DrawerCell(
    app: AppInfo,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    onDragStarted: (DragPayload) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale = animateIosPressScale(
        pressed = pressed,
        label = "drawerCellScale",
    )
    val haptic = LocalHapticFeedback.current

    val icon by rememberAppIconBitmap(app.ref)
    val payload = remember(app.ref) { DragPayload.FromDrawer(app.ref) }

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            .ohagiDragSource(
                payload = payload,
                icon = icon,
                onTap = onTap,
                onPressChanged = { pressed = it },
                onDragStarted = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDragStarted(payload)
                },
            )
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppIconImage(icon = icon, size = 56.dp)
            Spacer(Modifier.height(6.dp))
            // iOS ホーム風: アイコン下に最大 2 行のラベル。半透明背景で読めるよう白系。
            Text(
                text = app.label,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.1.sp,
                color = Kome,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = Shadow(color = Ink, offset = Offset(0f, 1f), blurRadius = 4f),
                ),
                maxLines = 2,
                minLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 30.dp),
            )
        }
        IosMoreButton(
            contentDescription = stringResource(R.string.action_more),
            onClick = onMenu,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp),
            size = 22.dp,
        )
    }
}

/** セル操作メニューのヘッダー（アイコン + ラベル）。 */
@Composable
private fun DrawerMenuHeader(app: AppInfo) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        AppIcon(app = app.ref, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Text(text = app.label)
    }
}
