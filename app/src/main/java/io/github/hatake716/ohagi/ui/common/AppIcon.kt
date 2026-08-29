package io.github.hatake716.ohagi.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.data.AppRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * アプリアイコンを非同期で読み込んで表示する共通コンポーザブル。
 * iPhone 風に角丸(辺長の約 22.37% = superellipse 近似)でマスクして統一感を出す。
 * 読み込み中はプレースホルダーの角丸ボックスを表示する。
 */
@Composable
fun AppIcon(
    app: AppRef,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val icon by rememberAppIconBitmap(app, size)
    AppIconImage(icon = icon, size = size, modifier = modifier)
}

/** すでに取得済みのBitmapを描画し、D&D装飾との二重購読・二重ロードを避ける。 */
@Composable
fun AppIconImage(
    icon: ImageBitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val iosShape = iosIconShape(size)

    Box(
        modifier = modifier
            .size(size)
            // 影はマスク前に置き、iOSホーム画面の控えめな接地感だけを再現する。
            .shadow(
                elevation = size * 0.035f,
                shape = iosShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.28f),
            )
            .clip(iosShape)
            .background(Color(0x24FFFFFF))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), iosShape),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** AppIcon と drag shadow が同じ非同期アイコン取得経路を共有するための状態。 */
@Composable
fun rememberAppIconBitmap(
    app: AppRef?,
    size: Dp? = null,
): State<ImageBitmap?> {
    if (app == null) return remember { mutableStateOf(null) }

    val graph = LocalGraph.current
    val requestedSizePx = size?.let { with(LocalDensity.current) { it.roundToPx() } }
    // iconVersion をキーに含め、アプリ更新でキャッシュが無効化されたら再読込する
    val iconVersion = graph.appRepository.iconVersion
    // LazyGridのセルが再利用されても直前のアプリBitmapを1フレーム表示しないよう、
    // app/iconVersionごとにState自体を作り直す。
    return key(app, requestedSizePx, iconVersion) {
        produceState<ImageBitmap?>(initialValue = null) {
            value = withContext(Dispatchers.IO) {
                if (requestedSizePx == null) {
                    graph.appRepository.iconOf(app)
                } else {
                    graph.appRepository.iconOf(app, requestedSizePx)
                }
            }
        }
    }
}

/** フォルダのドラッグ装飾用に、先頭ページのアイコンをまとめて非同期取得する。 */
@Composable
fun rememberAppIconBitmaps(apps: List<AppRef>): State<List<ImageBitmap?>> {
    val firstPage = remember(apps) { apps.take(9) }
    if (firstPage.isEmpty()) return remember { mutableStateOf(emptyList()) }

    val graph = LocalGraph.current
    val iconVersion = graph.appRepository.iconVersion
    return key(firstPage, iconVersion) {
        produceState<List<ImageBitmap?>>(initialValue = List(firstPage.size) { null }) {
            value = withContext(Dispatchers.IO) {
                firstPage.map { app -> graph.appRepository.iconOf(app) }
            }
        }
    }
}
