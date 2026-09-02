package io.github.hatake716.ohagi.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.util.FilePinUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ファイルピンのサムネイルを非同期取得する(画像/動画/PDF)。
 * ドラッグ影と表示が同じ Bitmap を共有できるよう State で返す。
 * 非対応の種類・取得失敗は null のままで、表示側は書類アイコンへフォールバックする。
 */
@Composable
fun rememberFilePinThumbnail(
    pin: HomeItem.HomeFile?,
    size: Dp,
): State<ImageBitmap?> {
    if (pin == null || !FilePinUtils.supportsThumbnail(pin.mimeType)) {
        return remember { mutableStateOf(null) }
    }
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    // セル再利用時に前のサムネイルを1フレーム見せないよう、キャッシュを初期値に使う。
    val cached = remember(pin.uri, sizePx) { FilePinUtils.cachedThumbnailOf(pin.uri) }
    return produceState(initialValue = cached, pin.uri, sizePx) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                FilePinUtils.thumbnailOf(context, pin.uri, pin.mimeType, sizePx)
            }
        }
    }
}

/**
 * ホームへピン留めしたファイルのアイコン(macOS の Finder/デスクトップ準拠)。
 * - サムネイルあり: 画像の縦横比を保ったまま、ごく小さい角丸と細い縁取りで表示
 *   (iOS 的な正方形クロップはしない)。動画のみ控えめな再生バッジを重ねる。
 * - サムネイルなし: 右上の角が折れた白い書類の上に、種類色の小さなシンボルを載せる。
 */
@Composable
fun FilePinIcon(
    mimeType: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    thumbnail: ImageBitmap? = null,
) {
    if (thumbnail != null) {
        val aspect = thumbnail.width.toFloat() / thumbnail.height.toFloat()
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.size(size),
        ) {
            Box(
                modifier = Modifier
                    // 枠内で縦横比を保った最大サイズ(macOS のプレビューアイコンと同じ)。
                    .aspectRatio(aspect.coerceIn(0.35f, 2.8f))
                    .clip(RoundedCornerShape(3.dp))
                    .border(
                        width = 0.75.dp,
                        color = Color.Black.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(3.dp),
                    ),
            ) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.aspectRatio(aspect.coerceIn(0.35f, 2.8f)),
                )
                if (FilePinUtils.kindOf(mimeType) == FilePinUtils.PinKind.VIDEO) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(size * 0.34f)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(size * 0.24f),
                        )
                    }
                }
            }
        }
        return
    }

    val kind = FilePinUtils.kindOf(mimeType)
    val (tint, symbol) = when (kind) {
        FilePinUtils.PinKind.IMAGE -> MacSymbolGreen to Icons.Rounded.Image
        FilePinUtils.PinKind.VIDEO -> MacSymbolPurple to Icons.Rounded.Videocam
        FilePinUtils.PinKind.AUDIO -> MacSymbolPink to Icons.Rounded.MusicNote
        FilePinUtils.PinKind.PDF -> MacSymbolRed to Icons.Rounded.PictureAsPdf
        FilePinUtils.PinKind.DOCUMENT -> MacSymbolBlue to Icons.Rounded.Description
        FilePinUtils.PinKind.ARCHIVE -> MacSymbolBrown to Icons.Rounded.Archive
        FilePinUtils.PinKind.OTHER ->
            MacSymbolGray to Icons.AutoMirrored.Rounded.InsertDriveFile
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size),
    ) {
        // 右上が折れた白い書類(macOS の汎用書類アイコン)。
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val paperW = w * 0.70f
            val paperH = h * 0.92f
            val left = (w - paperW) / 2f
            val top = (h - paperH) / 2f
            val right = left + paperW
            val bottom = top + paperH
            val fold = paperW * 0.30f

            val paper = Path().apply {
                moveTo(left, top)
                lineTo(right - fold, top)
                lineTo(right, top + fold)
                lineTo(right, bottom)
                lineTo(left, bottom)
                close()
            }
            drawPath(paper, color = MacPaper)
            drawPath(paper, color = MacPaperEdge, style = Stroke(width = w * 0.016f))

            // 折り返し(手前に倒れた三角)。
            val foldFlap = Path().apply {
                moveTo(right - fold, top)
                lineTo(right, top + fold)
                lineTo(right - fold, top + fold)
                close()
            }
            drawPath(foldFlap, color = MacPaperFold)
            drawPath(foldFlap, color = MacPaperEdge, style = Stroke(width = w * 0.014f))
        }
        Icon(
            imageVector = symbol,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = size * 0.14f)
                .size(size * 0.34f),
        )
    }
}

/**
 * ホームへピン留めした実フォルダのアイコン。
 * macOS(Big Sur 以降)の青いフォルダ形状をそのまま描く:
 * 濃い青の背面タブ + 上が明るい青のグラデーション本体。タイル背景は使わない。
 */
@Composable
fun DirectoryPinIcon(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // フォルダは横長。60dp 枠の中で幅いっぱい・縦は中央に寄せる。
        val bodyTop = h * 0.24f
        val bodyBottom = h * 0.84f
        val tabTop = h * 0.14f
        val corner = w * 0.10f

        // 背面(タブ): 左側だけ上に出る。
        drawRoundRect(
            color = MacFolderTab,
            topLeft = Offset(0f, tabTop),
            size = Size(w * 0.46f, bodyTop - tabTop + corner),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
        )
        // 本体: 上が明るい青のグラデーション。
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(MacFolderLight, MacFolderDark),
                startY = bodyTop,
                endY = bodyBottom,
            ),
            topLeft = Offset(0f, bodyTop),
            size = Size(w, bodyBottom - bodyTop),
            cornerRadius = CornerRadius(corner, corner),
        )
        // 本体上端のハイライト(蓋の面替わりの細い明るいライン)。
        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = Offset(w * 0.02f, bodyTop),
            size = Size(w * 0.96f, h * 0.035f),
            cornerRadius = CornerRadius(corner * 0.5f, corner * 0.5f),
        )
    }
}

// macOS Big Sur 以降のフォルダブルー(上面が明るいシアン寄り、下面が濃い青)。
private val MacFolderLight = Color(0xFF6FC0F5)
private val MacFolderDark = Color(0xFF3D96E8)
private val MacFolderTab = Color(0xFF3587D6)

// macOS の汎用書類(白い紙 + 薄いグレー縁 + 折り角)。
private val MacPaper = Color(0xFFFAFBFC)
private val MacPaperFold = Color(0xFFE2E6EC)
private val MacPaperEdge = Color(0xFFC3C9D2)

// 書類上の種類シンボル色(macOS のプレビュー/種類アイコンに寄せた控えめな色)。
private val MacSymbolBlue = Color(0xFF3E7BD6)
private val MacSymbolGreen = Color(0xFF3E9F55)
private val MacSymbolPurple = Color(0xFF8352C7)
private val MacSymbolPink = Color(0xFFD34B7E)
private val MacSymbolRed = Color(0xFFD23F3F)
private val MacSymbolBrown = Color(0xFF9A7A50)
private val MacSymbolGray = Color(0xFF7C8593)
