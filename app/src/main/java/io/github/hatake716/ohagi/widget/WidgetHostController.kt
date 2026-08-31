package io.github.hatake716.ohagi.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.util.SizeF
import io.github.hatake716.ohagi.data.WidgetPlacement
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Android標準AppWidgetHostをランチャー全体で1つだけ管理する。 */
class WidgetHostController(context: Context) {

    private val appContext = context.applicationContext
    private val manager = AppWidgetManager.getInstance(appContext)
    private val host = AppWidgetHost(appContext, HOST_ID)
    // Pagerから一度外れた直後の再入場では、まだ生存しているRemoteViewsを再利用する。
    // WeakReferenceなので非表示Widgetや古いActivityをRAMへ固定しない。
    private val reusableViews = ConcurrentHashMap<Int, WeakReference<AppWidgetHostView>>()
    private val lastWidgetSizes = ConcurrentHashMap<Int, WidgetSize>()

    fun startListening() {
        runCatching(host::startListening)
            .onFailure { Log.w(TAG, "ウィジェット更新の購読開始に失敗しました", it) }
    }

    fun stopListening() {
        runCatching(host::stopListening)
            .onFailure { Log.w(TAG, "ウィジェット更新の購読停止に失敗しました", it) }
    }

    fun allocateAppWidgetId(): Int = host.allocateAppWidgetId()

    fun deleteAppWidgetId(appWidgetId: Int) {
        reusableViews.remove(appWidgetId)
        lastWidgetSizes.remove(appWidgetId)
        runCatching { host.deleteAppWidgetId(appWidgetId) }
            .onFailure { Log.w(TAG, "ウィジェットIDの削除に失敗しました: $appWidgetId", it) }
    }

    fun appWidgetInfo(appWidgetId: Int): AppWidgetProviderInfo? =
        manager.getAppWidgetInfo(appWidgetId)

    fun installedProviders(): List<AppWidgetProviderInfo> =
        manager.getInstalledProvidersForProfile(Process.myUserHandle())
            .filter { info ->
                info.widgetCategory == 0 ||
                    info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
            }
            .sortedWith(
                compareBy<AppWidgetProviderInfo>(
                    { providerAppLabel(it).lowercase() },
                    { providerLabel(it).lowercase() },
                ),
            )

    fun providerLabel(info: AppWidgetProviderInfo): String =
        runCatching { info.loadLabel(appContext.packageManager) }
            .getOrNull()
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?: info.provider.shortClassName.substringAfterLast('.')

    fun providerAppLabel(info: AppWidgetProviderInfo): String =
        runCatching {
            val applicationInfo = appContext.packageManager.getApplicationInfo(
                info.provider.packageName,
                0,
            )
            appContext.packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(info.provider.packageName)

    fun bindIfAllowed(
        appWidgetId: Int,
        info: AppWidgetProviderInfo,
        options: Bundle,
    ): Boolean = runCatching {
        manager.bindAppWidgetIdIfAllowed(
            appWidgetId,
            info.profile,
            info.provider,
            options,
        )
    }.onFailure {
        Log.w(TAG, "ウィジェットの直接バインドに失敗しました: ${info.provider}", it)
    }.getOrDefault(false)

    fun createView(
        context: Context,
        appWidgetId: Int,
        info: AppWidgetProviderInfo,
    ): AppWidgetHostView {
        reusableViews[appWidgetId]?.get()?.let { cached ->
            if (cached.context === context && cached.parent == null) {
                return cached
            }
        }
        return host.createView(context, appWidgetId, info).apply {
            setAppWidget(appWidgetId, info)
            setPadding(0, 0, 0, 0)
            reusableViews[appWidgetId] = WeakReference(this)
        }
    }

    @Suppress("DEPRECATION")
    fun startConfiguration(
        activity: android.app.Activity,
        appWidgetId: Int,
        requestCode: Int,
        options: Bundle,
    ) {
        host.startAppWidgetConfigureActivityForResult(
            activity,
            appWidgetId,
            0,
            requestCode,
            options,
        )
    }

    fun bindingOptions(context: Context, heightDp: Int): Bundle {
        val widthDp = (context.resources.displayMetrics.widthPixels /
            context.resources.displayMetrics.density).roundToInt() - HORIZONTAL_MARGIN_DP
        return sizeOptions(widthDp.coerceAtLeast(1), heightDp)
    }

    fun updateSize(appWidgetId: Int, widthDp: Int, heightDp: Int) {
        val size = WidgetSize(widthDp, heightDp)
        if (lastWidgetSizes[appWidgetId] == size) return
        val options = sizeOptions(widthDp, heightDp)
        runCatching {
            val hostView = reusableViews[appWidgetId]?.get()
            if (hostView == null) {
                manager.updateAppWidgetOptions(appWidgetId, options)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hostView.updateAppWidgetSize(
                    options,
                    listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())),
                )
            } else {
                @Suppress("DEPRECATION")
                hostView.updateAppWidgetSize(
                    options,
                    widthDp,
                    heightDp,
                    widthDp,
                    heightDp,
                )
            }
        }
            .onSuccess { lastWidgetSizes[appWidgetId] = size }
            .onFailure {
                Log.w(TAG, "ウィジェットサイズ更新に失敗しました: $appWidgetId", it)
            }
    }

    fun placementFor(appWidgetId: Int, info: AppWidgetProviderInfo): WidgetPlacement {
        val density = appContext.resources.displayMetrics.density
        val providerHeightDp = (info.minHeight / density).roundToInt()
        return WidgetPlacement(
            appWidgetId = appWidgetId,
            providerPackage = info.provider.packageName,
            providerClass = info.provider.className,
            widthDp = WidgetPlacement.MATCH_PARENT_WIDTH_DP,
            heightDp = providerHeightDp.coerceIn(MIN_WIDGET_HEIGHT_DP, MAX_WIDGET_HEIGHT_DP),
        )
    }

    /** プロバイダーが宣言したresizeModeとdp制約を、現在の表示可能幅へ変換する。 */
    fun resizeBounds(
        info: AppWidgetProviderInfo,
        availableWidthDp: Int,
    ): WidgetResizeBounds {
        val density = appContext.resources.displayMetrics.density
        fun pxToDp(px: Int): Int = (px / density).roundToInt().coerceAtLeast(1)

        val horizontalDeclared =
            info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val verticalDeclared =
            info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val defaultWidthDp = pxToDp(info.minWidth)
        val defaultHeightDp = pxToDp(info.minHeight)
        val declaredMinWidthDp = info.minResizeWidth
            .takeIf { it > 0 }
            ?.let(::pxToDp)
        val declaredMinHeightDp = info.minResizeHeight
            .takeIf { it > 0 }
            ?.let(::pxToDp)

        val minimumWidthDp = if (horizontalDeclared) {
            declaredMinWidthDp ?: defaultWidthDp
        } else {
            defaultWidthDp
        }.coerceAtLeast(WidgetPlacement.MIN_WIDGET_WIDTH_DP)
        val minimumHeightDp = if (verticalDeclared) {
            declaredMinHeightDp ?: defaultHeightDp
        } else {
            defaultHeightDp
        }.coerceAtLeast(WidgetPlacement.MIN_WIDGET_HEIGHT_DP)

        val declaredMaxWidthDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.maxResizeWidth.takeIf { it > 0 }?.let(::pxToDp)
        } else {
            null
        }
        val declaredMaxHeightDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.maxResizeHeight.takeIf { it > 0 }?.let(::pxToDp)
        } else {
            null
        }
        val availableWidth = availableWidthDp.coerceAtLeast(1)
        val maximumWidthDp = declaredMaxWidthDp
            ?.coerceAtMost(availableWidth)
            ?: availableWidth
        val maximumHeightDp = declaredMaxHeightDp
            ?.coerceAtMost(WidgetPlacement.MAX_WIDGET_HEIGHT_DP)
            ?: WidgetPlacement.MAX_WIDGET_HEIGHT_DP

        val minWidthDp = minimumWidthDp.coerceAtMost(maximumWidthDp)
        val minHeightDp = minimumHeightDp.coerceAtMost(maximumHeightDp)
        return WidgetResizeBounds(
            canResizeHorizontally = horizontalDeclared && minWidthDp < maximumWidthDp,
            canResizeVertically = verticalDeclared && minHeightDp < maximumHeightDp,
            minWidthDp = minWidthDp,
            maxWidthDp = maximumWidthDp,
            minHeightDp = minHeightDp,
            maxHeightDp = maximumHeightDp,
        )
    }

    private fun sizeOptions(widthDp: Int, heightDp: Int): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        putInt(
            AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            putParcelableArrayList(
                AppWidgetManager.OPTION_APPWIDGET_SIZES,
                arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())),
            )
        }
    }

    fun componentOf(placement: WidgetPlacement): ComponentName = ComponentName(
        placement.providerPackage,
        placement.providerClass,
    )

    private companion object {
        const val HOST_ID = 716
        const val HORIZONTAL_MARGIN_DP = 32
        const val MIN_WIDGET_HEIGHT_DP = 120
        const val MAX_WIDGET_HEIGHT_DP = 360
        const val TAG = "WidgetHost"
    }

    private data class WidgetSize(
        val widthDp: Int,
        val heightDp: Int,
    )
}

data class WidgetResizeBounds(
    val canResizeHorizontally: Boolean,
    val canResizeVertically: Boolean,
    val minWidthDp: Int,
    val maxWidthDp: Int,
    val minHeightDp: Int,
    val maxHeightDp: Int,
)
