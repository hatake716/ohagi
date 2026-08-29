package io.github.hatake716.ohagi.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
    val icon by rememberAppIconBitmap(app)
    val iosShape = iosIconShape(size)
    val current = icon

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
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** AppIcon と drag shadow が同じ非同期アイコン取得経路を共有するための状態。 */
@Composable
fun rememberAppIconBitmap(app: AppRef?): State<ImageBitmap?> {
    val graph = LocalGraph.current
    // iconVersion をキーに含め、アプリ更新でキャッシュが無効化されたら再読込する
    val iconVersion by graph.appRepository.iconVersion.collectAsState()
    // LazyGridのセルが再利用されても直前のアプリBitmapを1フレーム表示しないよう、
    // app/iconVersionごとにState自体を作り直す。
    return key(app, iconVersion) {
        produceState<ImageBitmap?>(initialValue = null) {
            value = if (app == null) null else withContext(Dispatchers.IO) {
                graph.appRepository.iconOf(app)
            }
        }
    }
}
