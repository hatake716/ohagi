package io.github.hatake716.ohagi.ui.folder

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.FolderLocation
import io.github.hatake716.ohagi.ui.common.AppIconImage
import io.github.hatake716.ohagi.ui.common.IOS_SELECTION_BLUE
import io.github.hatake716.ohagi.ui.common.IosGlassIconButton
import io.github.hatake716.ohagi.ui.common.animateIosPressScale
import io.github.hatake716.ohagi.ui.common.rememberAppIconBitmap
import io.github.hatake716.ohagi.ui.dragdrop.DragPayload
import io.github.hatake716.ohagi.ui.dragdrop.folderLocationOrNull
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDragSource
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDropTarget
import io.github.hatake716.ohagi.ui.dragdrop.rememberOhagiDropTarget
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome
import kotlin.math.max

private const val APPS_PER_FOLDER_PAGE = 9

/**
 * ホームとDockで共用するiOS風フォルダ。
 *
 * - 中央の半透明パネル
 * - 1ページ3×3、複数ページとページドット
 * - 長押しD&Dによる並べ替えとフォルダ外への移動
 * - 編集モードの揺れと削除ボタン
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IosFolderOverlay(
    location: FolderLocation,
    folderName: String,
    apps: List<AppRef>,
    labelOf: (AppRef) -> String,
    activeDrag: DragPayload?,
    onLaunch: (AppRef) -> Unit,
    onAddApps: () -> Unit,
    onRemoveApp: (AppRef) -> Unit,
    onRename: () -> Unit,
    onReorder: (Int, Int) -> Unit,
    onDragMoved: (Offset) -> Unit,
    onDragStarted: (DragPayload) -> Unit,
    onDragEnded: () -> Unit,
    onDragOutside: () -> Unit,
    onDismiss: () -> Unit,
) {
    var entered by remember(location) { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var panelBounds by remember { mutableStateOf<Rect?>(null) }
    var dragOutRequested by remember { mutableStateOf(false) }
    var sessionPayload by remember { mutableStateOf<DragPayload?>(null) }

    val pageCount = max(1, (apps.size + APPS_PER_FOLDER_PAGE - 1) / APPS_PER_FOLDER_PAGE)
    val pagerState = rememberPagerState(pageCount = { pageCount })
    LaunchedEffect(location) { entered = true }
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f),
        label = "folderOpenReveal",
    )
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount) {
            pagerState.scrollToPage(pageCount - 1)
        }
    }
    LaunchedEffect(activeDrag) {
        if (activeDrag == null) {
            dragOutRequested = false
            sessionPayload = null
        }
    }

    fun isFromThisFolder(payload: DragPayload?): Boolean =
        payload?.folderLocationOrNull() == location

    fun handleDragMoved(position: Offset) {
        onDragMoved(position)
        val bounds = panelBounds
        if (!dragOutRequested &&
            isFromThisFolder(sessionPayload ?: activeDrag) &&
            bounds != null &&
            !bounds.contains(position)
        ) {
            dragOutRequested = true
            onDragOutside()
        }
    }

    fun handleDragStarted(payload: DragPayload) {
        // HomeScreen側の再構成を待たず、このOSセッションの開始payloadを即時保持する。
        sessionPayload = payload
        onDragStarted(payload)
    }

    fun handleDragEnded() {
        sessionPayload = null
        onDragEnded()
    }

    val backgroundDropTarget = rememberOhagiDropTarget(
        onStarted = ::handleDragStarted,
        onMoved = ::handleDragMoved,
        onEnded = ::handleDragEnded,
        onDrop = { _, _ -> false },
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f * reveal))
            .ohagiDropTarget(backgroundDropTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .safeDrawingPadding()
            .padding(horizontal = 18.dp, vertical = 30.dp),
    ) {
        val panelShape = RoundedCornerShape(38.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 390.dp)
                .fillMaxWidth()
                .heightIn(min = 430.dp, max = 520.dp)
                .graphicsLayer {
                    alpha = reveal
                    val scale = 0.80f + reveal * 0.20f
                    scaleX = scale
                    scaleY = scale
                }
                .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                .shadow(
                    elevation = 26.dp,
                    shape = panelShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.30f),
                    spotColor = Color.Black.copy(alpha = 0.40f),
                )
                .clip(panelShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xE146444D), Color(0xE1222027)),
                    ),
                )
                .border(0.75.dp, Color.White.copy(alpha = 0.24f), panelShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(top = 10.dp, bottom = 14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                TextButton(onClick = { editMode = !editMode }) {
                    Text(
                        text = stringResource(
                            if (editMode) R.string.action_done else R.string.folder_edit,
                        ),
                        color = IOS_SELECTION_BLUE,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onRename,
                        )
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = folderName,
                        color = Kome,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.action_rename),
                        tint = Kome.copy(alpha = 0.58f),
                        modifier = Modifier.size(16.dp),
                    )
                }

                IosGlassIconButton(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.folder_add_apps),
                    onClick = onAddApps,
                    size = 38.dp,
                )
            }

            HorizontalPager(
                state = pagerState,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(342.dp),
            ) { page ->
                FolderPage(
                    location = location,
                    page = page,
                    apps = apps,
                    labelOf = labelOf,
                    activeDrag = activeDrag,
                    editMode = editMode,
                    onLaunch = onLaunch,
                    onRemoveApp = onRemoveApp,
                    onReorder = onReorder,
                    onDragMoved = ::handleDragMoved,
                    onDragStarted = ::handleDragStarted,
                    onDragEnded = ::handleDragEnded,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(20.dp),
            ) {
                repeat(pageCount) { page ->
                    val selected = pagerState.currentPage == page
                    val color by animateColorAsState(
                        targetValue = if (selected) {
                            Kome.copy(alpha = 0.95f)
                        } else {
                            Kome.copy(alpha = 0.30f)
                        },
                        animationSpec = tween(durationMillis = 120),
                        label = "folderPageDot",
                    )
                    Box(
                        Modifier
                            .size(if (selected) 7.dp else 6.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderPage(
    location: FolderLocation,
    page: Int,
    apps: List<AppRef>,
    labelOf: (AppRef) -> String,
    activeDrag: DragPayload?,
    editMode: Boolean,
    onLaunch: (AppRef) -> Unit,
    onRemoveApp: (AppRef) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onDragMoved: (Offset) -> Unit,
    onDragStarted: (DragPayload) -> Unit,
    onDragEnded: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        repeat(3) { row ->
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                repeat(3) { column ->
                    val globalIndex = page * APPS_PER_FOLDER_PAGE + row * 3 + column
                    val app = apps.getOrNull(globalIndex)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (app != null) {
                            FolderAppCell(
                                location = location,
                                app = app,
                                appIndex = globalIndex,
                                labelOf = labelOf,
                                activeDrag = activeDrag,
                                editMode = editMode,
                                onLaunch = { onLaunch(app) },
                                onRemove = { onRemoveApp(app) },
                                onReorder = onReorder,
                                onDragMoved = onDragMoved,
                                onDragStarted = onDragStarted,
                                onDragEnded = onDragEnded,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderAppCell(
    location: FolderLocation,
    app: AppRef,
    appIndex: Int,
    labelOf: (AppRef) -> String,
    activeDrag: DragPayload?,
    editMode: Boolean,
    onLaunch: () -> Unit,
    onRemove: () -> Unit,
    onReorder: (Int, Int) -> Unit,
    onDragMoved: (Offset) -> Unit,
    onDragStarted: (DragPayload) -> Unit,
    onDragEnded: () -> Unit,
) {
    val label = labelOf(app)
    var pressed by remember { mutableStateOf(false) }
    var dropHovered by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val icon by rememberAppIconBitmap(app)
    val payload = remember(location, appIndex, app) {
        when (location) {
            is FolderLocation.Home ->
                DragPayload.FromHomeFolder(location.index, appIndex, app)
            is FolderLocation.Dock ->
                DragPayload.FromDockFolder(location.slot, appIndex, app)
        }
    }
    val isDragging = activeDrag == payload

    val scale = animateIosPressScale(
        pressed = pressed || dropHovered,
        pressedScale = if (dropHovered) 1.08f else 0.94f,
        label = "folderAppScale",
    )
    // 通常表示中は無限アニメーションをcompositionから外し、編集時だけ揺らす。
    val wiggle = if (editMode) {
        val infiniteTransition = rememberInfiniteTransition(label = "folderWiggle")
        val animatedWiggle by infiniteTransition.animateFloat(
            initialValue = -1.25f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 135 + (appIndex % 3) * 12),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "folderWiggleAngle",
        )
        animatedWiggle
    } else {
        0f
    }

    val dropTarget = rememberOhagiDropTarget(
        onStarted = onDragStarted,
        onEntered = { dropHovered = true },
        onMoved = onDragMoved,
        onExited = { dropHovered = false },
        onEnded = {
            dropHovered = false
            onDragEnded()
        },
        onDrop = { dropped, _ ->
            dropHovered = false
            val fromIndex = when (dropped) {
                is DragPayload.FromHomeFolder ->
                    if (location == FolderLocation.Home(dropped.index)) dropped.appIndex else null
                is DragPayload.FromDockFolder ->
                    if (location == FolderLocation.Dock(dropped.slot)) dropped.appIndex else null
                else -> null
            } ?: return@rememberOhagiDropTarget false
            onReorder(fromIndex, appIndex)
            true
        },
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(92.dp)
            .alpha(if (isDragging) 0.18f else 1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = if (editMode) wiggle else 0f
            }
            .clip(RoundedCornerShape(18.dp))
            .ohagiDropTarget(dropTarget)
            .ohagiDragSource(
                payload = payload,
                icon = icon,
                onTap = { if (!editMode) onLaunch() },
                onPressChanged = { pressed = it },
                onDragStarted = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDragStarted(payload)
                },
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Box {
            AppIconImage(icon = icon, size = 58.dp)
            if (editMode) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF2F2F7))
                        .border(0.5.dp, Color.Black.copy(alpha = 0.18f), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onRemove,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = stringResource(R.string.folder_remove_app, label),
                        tint = Color(0xFF3A3A3C),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            color = Kome,
            style = MaterialTheme.typography.labelMedium,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
