package io.github.hatake716.ohagi.ui.dragdrop

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.FolderLocation
import io.github.hatake716.ohagi.ui.common.IOS_ICON_CORNER_RATIO
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.math.roundToInt

/** ohagi 内の公式 Drag and Drop セッションで運ぶデータ。 */
@Serializable
sealed interface DragPayload {
    @Serializable
    @SerialName("home")
    data class FromHome(val index: Int) : DragPayload

    @Serializable
    @SerialName("dock")
    data class FromDock(val slot: Int) : DragPayload

    @Serializable
    @SerialName("drawer")
    data class FromDrawer(val app: AppRef) : DragPayload

    @Serializable
    @SerialName("home-folder-app")
    data class FromHomeFolder(
        val index: Int,
        val appIndex: Int,
        val app: AppRef,
    ) : DragPayload

    @Serializable
    @SerialName("dock-folder-app")
    data class FromDockFolder(
        val slot: Int,
        val appIndex: Int,
        val app: AppRef,
    ) : DragPayload
}

const val OHAGI_DND_MIME = "application/vnd.ohagi.dragitem"
const val OHAGI_REMOVABLE_DND_MIME = "application/vnd.ohagi.dragitem-removable"

private val dragPayloadJson = Json {
    ignoreUnknownKeys = true
}

/** 同一プロセスでは localState、再構成等で失われた場合は ClipData JSON を使う。 */
fun DragPayload.toTransferData(): DragAndDropTransferData {
    val encoded = dragPayloadJson.encodeToString(DragPayload.serializer(), this)
    val mimeTypes = if (this is DragPayload.FromDrawer) {
        arrayOf(OHAGI_DND_MIME)
    } else {
        arrayOf(OHAGI_DND_MIME, OHAGI_REMOVABLE_DND_MIME)
    }
    val clipData = ClipData(
        "ohagi",
        mimeTypes,
        ClipData.Item(encoded),
    )
    return DragAndDropTransferData(
        clipData = clipData,
        localState = this,
        flags = 0,
    )
}

fun DragAndDropEvent.isOhagiDrag(): Boolean = OHAGI_DND_MIME in mimeTypes()

fun DragAndDropEvent.isOhagiRemovableDrag(): Boolean =
    OHAGI_REMOVABLE_DND_MIME in mimeTypes()

fun DragPayload.isRemovable(): Boolean = this !is DragPayload.FromDrawer

fun DragPayload.folderLocationOrNull(): FolderLocation? = when (this) {
    is DragPayload.FromHomeFolder -> FolderLocation.Home(index)
    is DragPayload.FromDockFolder -> FolderLocation.Dock(slot)
    else -> null
}

fun DragAndDropEvent.readOhagiPayload(): DragPayload? {
    if (!isOhagiDrag()) return null
    val androidEvent = toAndroidDragEvent()
    (androidEvent.localState as? DragPayload)?.let { return it }
    val text = androidEvent.clipData
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.text
        ?.toString()
        ?: return null
    return runCatching {
        dragPayloadJson.decodeFromString(DragPayload.serializer(), text)
    }.getOrNull()
}

/** Android DragEvent の座標はComposeViewルート基準なので、boundsInRootと直接比較できる。 */
fun DragAndDropEvent.positionInOhagiRoot(): Offset {
    val event = toAndroidDragEvent()
    return Offset(event.x, event.y)
}

/** ohagi の独自 MIME だけを受け取る target modifier。 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.ohagiDropTarget(
    target: DragAndDropTarget,
    accept: (DragAndDropEvent) -> Boolean = { it.isOhagiDrag() },
): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = accept,
    target = target,
)

/**
 * コールバックを再構成後も最新に保った、ohagi 共通の公式D&Dターゲット。
 * payload の解析を各セルで重複させず、開始・終了も同じセッションとして通知する。
 */
@Composable
fun rememberOhagiDropTarget(
    onStarted: (DragPayload) -> Unit = {},
    onEntered: () -> Unit = {},
    onMoved: (Offset) -> Unit = {},
    onExited: () -> Unit = {},
    onEnded: () -> Unit = {},
    onDrop: (DragPayload, Offset) -> Boolean,
): DragAndDropTarget {
    val currentStarted = rememberUpdatedState(onStarted)
    val currentEntered = rememberUpdatedState(onEntered)
    val currentMoved = rememberUpdatedState(onMoved)
    val currentExited = rememberUpdatedState(onExited)
    val currentEnded = rememberUpdatedState(onEnded)
    val currentDrop = rememberUpdatedState(onDrop)

    return remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                event.readOhagiPayload()?.let(currentStarted.value)
            }

            override fun onEntered(event: DragAndDropEvent) {
                currentEntered.value()
            }

            override fun onMoved(event: DragAndDropEvent) {
                currentMoved.value(event.positionInOhagiRoot())
            }

            override fun onExited(event: DragAndDropEvent) {
                currentExited.value()
            }

            override fun onEnded(event: DragAndDropEvent) {
                currentEnded.value()
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val payload = event.readOhagiPayload() ?: return false
                val position = event.positionInOhagiRoot()
                return currentDrop.value(payload, position)
            }
        }
    }
}

/**
 * 公式dragAndDropSourceの検出ブロックで、短いタップと長押しD&Dを一元処理する。
 * 別のclickableとDOWNを取り合わないため、LazyGrid内でもドラッグ開始が安定する。
 *
 * [onLongPressMenu] を渡すと iOS 本来の長押し体験になる:
 * 長押し成立でリフト([onDragStarted])し、そのまま動かせばドラッグ、
 * 動かさず離せばメニュー([onLongPressMenu])。画面上のメニューボタンを置かずに
 * ドラッグとコンテキストメニューを1つの長押しに同居させるための分岐で、
 * null の場合は従来どおり長押し成立で即ドラッグセッションを開始する。
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
@Composable
fun Modifier.ohagiDragSource(
    payload: DragPayload,
    icon: ImageBitmap?,
    folderIcons: List<ImageBitmap?> = emptyList(),
    onTap: () -> Unit,
    onPressChanged: (Boolean) -> Unit = {},
    /** 長押し成立(リフト)時。ハプティクスやリフト演出用。メニューに化けても呼ばれる。 */
    onLift: () -> Unit = {},
    /** 実際にドラッグセッションを開始する(startTransfer)直前。親への通知はこちらで。 */
    onDragStarted: () -> Unit = {},
    onLongPressMenu: (() -> Unit)? = null,
): Modifier {
    // animateItemの安定keyで同じComposableが別slotへ移動しても、D&D nodeが
    // 移動前indexのlambdaを保持しないよう、セッション開始時に最新値を読む。
    val currentPayload = rememberUpdatedState(payload)
    val currentIcon = rememberUpdatedState(icon)
    val currentFolderIcons = rememberUpdatedState(folderIcons)
    val currentTap = rememberUpdatedState(onTap)
    val currentPressChanged = rememberUpdatedState(onPressChanged)
    val currentLift = rememberUpdatedState(onLift)
    val currentDragStarted = rememberUpdatedState(onDragStarted)
    val currentLongPressMenu = rememberUpdatedState(onLongPressMenu)

    return dragAndDropSource(
        drawDragDecoration = {
            val latestFolderIcons = currentFolderIcons.value
            if (latestFolderIcons.isEmpty()) {
                drawOhagiDragDecoration(currentIcon.value)
            } else {
                drawOhagiFolderDragDecoration(latestFolderIcons)
            }
        },
        block = {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                currentPressChanged.value(true)
                try {
                    // 長押し成立を待つ。この間の slop 超えの動きはスクロールへ譲る
                    // (awaitLongPressOrCancellation が null を返す)。
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        // slop 内で指が離れていればタップ。まだ押されたままなら
                        // スクロール等に奪われたので何もしない。
                        val last = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (last == null || !last.pressed) currentTap.value()
                        return@awaitEachGesture
                    }

                    // 長押し成立: リフト開始(ハプティクス等は呼び出し側)。
                    longPress.consume()
                    currentLift.value()

                    val menu = currentLongPressMenu.value
                    if (menu == null) {
                        // 従来挙動: 即ドラッグセッション開始(以降は OS が追従)。
                        currentDragStarted.value()
                        startTransfer(currentPayload.value.toTransferData())
                        return@awaitEachGesture
                    }

                    // iOS 風分岐: slop を超えて動いたらドラッグ、動かさず離したらメニュー。
                    val slop = viewConfiguration.touchSlop
                    var moved = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) {
                            change.consume()
                            menu()
                            break
                        }
                        moved += change.positionChange()
                        change.consume()
                        if (moved.getDistance() > slop) {
                            currentDragStarted.value()
                            startTransfer(currentPayload.value.toTransferData())
                            break
                        }
                    }
                } finally {
                    currentPressChanged.value(false)
                }
            }
        },
    ).semantics {
        onClick {
            currentTap.value()
            true
        }
    }
}

private fun DrawScope.drawOhagiDragDecoration(icon: ImageBitmap?) {
    // 元アイコンより約1.1倍大きい半透明previewを、影の余白を残して指下へ浮かせる。
    val iconSize = min(size.minDimension * 0.94f, 76.dp.toPx())
    val left = (size.width - iconSize) / 2f
    val top = (size.height - iconSize) / 2f
    val corner = iconSize * IOS_ICON_CORNER_RATIO
    val farShadowOffset = min(5.dp.toPx(), (size.height - iconSize).coerceAtLeast(2f) / 2f)
    val nearShadowOffset = min(2.5.dp.toPx(), farShadowOffset * 0.55f)

    // DragDecorationではblurを使わず、広い影と接地影を重ねてiOSのlift感を近似する。
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.11f),
        topLeft = Offset(left, top + farShadowOffset),
        size = Size(iconSize, iconSize),
        cornerRadius = CornerRadius(corner, corner),
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.25f),
        topLeft = Offset(left, top + nearShadowOffset),
        size = Size(iconSize, iconSize),
        cornerRadius = CornerRadius(corner, corner),
    )
    if (icon != null) {
        val clip = Path().apply {
            addRoundRect(
                RoundRect(
                    left = left,
                    top = top,
                    right = left + iconSize,
                    bottom = top + iconSize,
                    cornerRadius = CornerRadius(corner, corner),
                ),
            )
        }
        clipPath(clip) {
            drawImage(
                image = icon,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(iconSize.roundToInt(), iconSize.roundToInt()),
                alpha = 0.97f,
            )
        }
    } else {
        drawRoundRect(
            color = Color(0xFFE8D8C4),
            topLeft = Offset(left, top),
            size = Size(iconSize, iconSize),
            cornerRadius = CornerRadius(corner, corner),
        )
    }
    drawRoundRect(
        color = Color.White.copy(alpha = 0.20f),
        topLeft = Offset(left, top),
        size = Size(iconSize, iconSize),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(width = 0.75.dp.toPx()),
    )
}

/** フォルダ本体を移動中も、単体アプリではなく3×3プレビューの影を表示する。 */
private fun DrawScope.drawOhagiFolderDragDecoration(icons: List<ImageBitmap?>) {
    val folderSize = min(size.minDimension * 0.94f, 76.dp.toPx())
    val left = (size.width - folderSize) / 2f
    val top = (size.height - folderSize) / 2f
    val corner = folderSize * IOS_ICON_CORNER_RATIO
    val miniSize = folderSize * 0.205f
    val spacing = folderSize * 0.055f
    val contentSize = miniSize * 3f + spacing * 2f
    val contentLeft = left + (folderSize - contentSize) / 2f
    val contentTop = top + (folderSize - contentSize) / 2f

    val farShadowOffset = min(5.dp.toPx(), (size.height - folderSize).coerceAtLeast(2f) / 2f)
    val nearShadowOffset = min(2.5.dp.toPx(), farShadowOffset * 0.55f)
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.11f),
        topLeft = Offset(left, top + farShadowOffset),
        size = Size(folderSize, folderSize),
        cornerRadius = CornerRadius(corner, corner),
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.23f),
        topLeft = Offset(left, top + nearShadowOffset),
        size = Size(folderSize, folderSize),
        cornerRadius = CornerRadius(corner, corner),
    )
    drawRoundRect(
        color = Color(0xCC55575E),
        topLeft = Offset(left, top),
        size = Size(folderSize, folderSize),
        cornerRadius = CornerRadius(corner, corner),
    )

    repeat(9) { index ->
        val row = index / 3
        val column = index % 3
        val miniLeft = contentLeft + column * (miniSize + spacing)
        val miniTop = contentTop + row * (miniSize + spacing)
        val miniCorner = miniSize * IOS_ICON_CORNER_RATIO
        val bitmap = icons.getOrNull(index)
        if (bitmap == null) {
            if (index < icons.size) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.12f),
                    topLeft = Offset(miniLeft, miniTop),
                    size = Size(miniSize, miniSize),
                    cornerRadius = CornerRadius(miniCorner, miniCorner),
                )
            }
        } else {
            val clip = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = miniLeft,
                        top = miniTop,
                        right = miniLeft + miniSize,
                        bottom = miniTop + miniSize,
                        cornerRadius = CornerRadius(miniCorner, miniCorner),
                    ),
                )
            }
            clipPath(clip) {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(miniLeft.roundToInt(), miniTop.roundToInt()),
                    dstSize = IntSize(miniSize.roundToInt(), miniSize.roundToInt()),
                    alpha = 0.97f,
                )
            }
        }
    }

    drawRoundRect(
        color = Color.White.copy(alpha = 0.25f),
        topLeft = Offset(left, top),
        size = Size(folderSize, folderSize),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(width = 0.75.dp.toPx()),
    )
}
