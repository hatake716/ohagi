package io.github.hatake716.ohagi.ui.common

import android.view.Surface
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView

/**
 * 端末の物理的な向きに合わせた UI 補正角(度)。0 / +90 / -90 のいずれか。
 *
 * Activity は回転を許可する(マウスポインタ等の入力は OS が横向きとして正しく扱う)。
 * ただしホーム/ドック/フォルダは iOS 同様レイアウトを回さない:
 * [PortraitStage] が UI 全体を縦向き寸法のまま横画面へ回して描き(=物理配置を維持)、
 * その中でアイコンや名称だけがこの補正角([uprightWithDevice])で直立する。
 */
val LocalDeviceUprightRotation = compositionLocalOf { 0f }

/**
 * 表示回転(Display.rotation)から直立補正角を求める。
 * 構成変更(LocalConfiguration)に追従して再計算される。
 * - ROTATION_90 (端末を左へ倒した横画面) → +90
 * - ROTATION_270 (右へ倒した横画面) → -90
 * - それ以外(縦・逆さ) → 0
 */
@Composable
fun rememberDeviceUprightRotation(): State<Float> {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val rotation = remember(configuration, view) {
        when (view.display?.rotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_270 -> -90f
            else -> 0f
        }
    }
    return rememberUpdatedState(rotation)
}

/**
 * 横画面時も UI を縦向き(portrait)の寸法・配置のまま描くためのステージ。
 * 横画面では幅と高さを入れ替えた領域に content を描き、逆方向へ 90 度回して
 * 物理的に「縦持ちと同じ位置」へ見せる。縦画面では素通し。
 * App ライブラリのように本当の横レイアウトにしたいページは、この中で
 * さらに [LocalDeviceUprightRotation] 分を逆回転して打ち消す。
 */
@Composable
fun PortraitStage(content: @Composable () -> Unit) {
    val upright = LocalDeviceUprightRotation.current
    if (upright == 0f) {
        content()
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .requiredSize(width = maxHeight, height = maxWidth)
                .align(Alignment.Center)
                .graphicsLayer { rotationZ = -upright },
        ) {
            content()
        }
    }
}

/** [LocalDeviceUprightRotation] へスプリングで追従する表示用の角度。 */
@Composable
fun animatedUprightRotation(): Float = rememberAnimatedUprightRotation().value

/** レイアウトを変えない回転では、StateをgraphicsLayerから直接読む。 */
@Composable
internal fun rememberAnimatedUprightRotation(): State<Float> {
    val target = LocalDeviceUprightRotation.current
    return animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "uprightRotation",
    )
}

/**
 * アイコン+名称のブロックを端末の向きへ合わせて立て直す。
 * レイアウト位置(セル/スロット)は変えず、その場で回転だけを行う。
 */
@Composable
fun Modifier.uprightWithDevice(): Modifier {
    val rotation = rememberAnimatedUprightRotation()
    return graphicsLayer { rotationZ = rotation.value }
}
