package io.github.hatake716.ohagi.ui.dragdrop

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
                return currentDrop.value(payload, event.positionInOhagiRoot())
            }
        }
    }
}

/**
 * 公式dragAndDropSourceの検出ブロックで、短いタップと長押しD&Dを一元処理する。
 * 別のclickableとDOWNを取り合わないため、LazyGrid内でもドラッグ開始が安定する。
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
fun Modifier.ohagiDragSource(
    payload: DragPayload,
    icon: ImageBitmap?,
    onTap: () -> Unit,
    onPressChanged: (Boolean) -> Unit = {},
    onDragStarted: () -> Unit = {},
): Modifier = dragAndDropSource(
    drawDragDecoration = { drawOhagiDragDecoration(icon) },
    block = {
        detectTapGestures(
            onPress = {
                onPressChanged(true)
                try {
                    tryAwaitRelease()
                } finally {
                    onPressChanged(false)
                }
            },
            onTap = { onTap() },
            onLongPress = {
                val transferData = payload.toTransferData()
                startTransfer(transferData)
                onDragStarted()
            },
        )
    },
).semantics {
    onClick {
        onTap()
        true
    }
}

private fun DrawScope.drawOhagiDragDecoration(icon: ImageBitmap?) {
    val iconSize = min(size.minDimension, 72.dp.toPx())
    val left = (size.width - iconSize) / 2f
    val top = (size.height - iconSize) / 2f
    val corner = iconSize * IOS_ICON_CORNER_RATIO

    // ぼかしを使えないDragDecorationでも硬く見えないよう、二層の影で持ち上がりを表す。
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.14f),
        topLeft = Offset(left, top + 6.dp.toPx()),
        size = Size(iconSize, iconSize),
        cornerRadius = CornerRadius(corner, corner),
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.22f),
        topLeft = Offset(left, top + 3.dp.toPx()),
        size = Size(iconSize, iconSize),
        cornerRadius = CornerRadius(corner, corner),
    )
    if (icon != null) {
        drawImage(
            image = icon,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(iconSize.roundToInt(), iconSize.roundToInt()),
        )
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
