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
import io.github.hatake716.ohagi.data.AppIconRequest
import io.github.hatake716.ohagi.data.AppRef

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
    decorated: Boolean = true,
) {
    val iosShape = iosIconShape(size)

    if (!decorated) {
        if (icon != null) {
            // Repository側ですでに角丸・Hardware Bitmap化済み。密集プレビューでは
            // アイコンごとのshadow/clip/borderレイヤーを重ねず、そのまま描画する。
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = modifier.size(size),
            )
        } else {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(iosShape)
                    .background(Color(0x24FFFFFF)),
            )
        }
        return
    }

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
    val cached = remember(app, requestedSizePx, iconVersion) {
        if (requestedSizePx == null) {
            graph.appRepository.cachedIconOf(app)
        } else {
            graph.appRepository.cachedIconOf(app, requestedSizePx)
        }
    }
    if (cached != null) {
        return remember(app, requestedSizePx, iconVersion, cached) {
            mutableStateOf(cached)
        }
    }
    // LazyGridのセルが再利用されても直前のアプリBitmapを1フレーム表示しないよう、
    // app/iconVersionごとにState自体を作り直す。
    return key(app, requestedSizePx, iconVersion) {
        produceState<ImageBitmap?>(initialValue = null) {
            value = if (requestedSizePx == null) {
                graph.appRepository.loadIcon(app)
            } else {
                graph.appRepository.loadIcon(app, requestedSizePx)
            }
        }
    }
}

/** フォルダのドラッグ装飾用に、先頭ページのアイコンをまとめて非同期取得する。 */
@Composable
fun rememberAppIconBitmaps(
    apps: List<AppRef>,
    size: Dp? = null,
): State<List<ImageBitmap?>> {
    val firstPage = remember(apps) { apps.take(9) }
    if (firstPage.isEmpty()) return remember { mutableStateOf(emptyList()) }

    val graph = LocalGraph.current
    val requestedSizePx = size?.let { with(LocalDensity.current) { it.roundToPx() } }
    val iconVersion = graph.appRepository.iconVersion
    val cached = remember(firstPage, requestedSizePx, iconVersion) {
        firstPage.map { app ->
            if (requestedSizePx == null) {
                graph.appRepository.cachedIconOf(app)
            } else {
                graph.appRepository.cachedIconOf(app, requestedSizePx)
            }
        }
    }
    if (cached.all { it != null }) {
        return remember(firstPage, requestedSizePx, iconVersion, cached) {
            mutableStateOf(cached)
        }
    }
    return key(firstPage, requestedSizePx, iconVersion) {
        produceState<List<ImageBitmap?>>(initialValue = cached) {
            value = firstPage.mapIndexed { index, app ->
                cached[index] ?: if (requestedSizePx == null) {
                    graph.appRepository.loadIcon(app)
                } else {
                    graph.appRepository.loadIcon(app, requestedSizePx)
                }
            }
        }
    }
}

/** サイズが混在するカテゴリカード用に、複数アイコンを1つのStateで取得する。 */
@Composable
fun rememberRequestedAppIconBitmaps(
    requests: List<AppIconRequest>,
): State<List<ImageBitmap?>> {
    if (requests.isEmpty()) return remember { mutableStateOf(emptyList()) }

    val graph = LocalGraph.current
    val iconVersion = graph.appRepository.iconVersion
    val cached = remember(requests, iconVersion) {
        requests.map { request ->
            graph.appRepository.cachedIconOf(request.ref, request.requestedSizePx)
        }
    }
    if (cached.all { it != null }) {
        return remember(requests, iconVersion, cached) { mutableStateOf(cached) }
    }
    return key(requests, iconVersion) {
        produceState<List<ImageBitmap?>>(initialValue = cached) {
            value = requests.mapIndexed { index, request ->
                cached[index] ?: graph.appRepository.loadIcon(
                    request.ref,
                    request.requestedSizePx,
                )
            }
        }
    }
}
