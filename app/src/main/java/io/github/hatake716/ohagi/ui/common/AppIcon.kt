package io.github.hatake716.ohagi.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
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
    val graph = LocalGraph.current
    // iconVersion をキーに含め、アプリ更新でキャッシュが無効化されたら再読込する
    val iconVersion by graph.appRepository.iconVersion.collectAsState()
    val icon by produceState<ImageBitmap?>(initialValue = null, app, iconVersion) {
        value = withContext(Dispatchers.IO) { graph.appRepository.iconOf(app) }
    }
    // iPhone のアイコン角丸に近い比率
    val iosShape = RoundedCornerShape(size * 0.2237f)
    val current = icon
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(iosShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(iosShape)
                .background(Color(0x33FFFFFF))
        )
    }
}
