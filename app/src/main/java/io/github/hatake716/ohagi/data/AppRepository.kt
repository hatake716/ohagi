package io.github.hatake716.ohagi.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Collator
import java.util.Locale

/** ドロワー表示用のアプリ情報 */
data class AppInfo(
    val ref: AppRef,
    val label: String,
    val category: AppCategory,
)

/**
 * インストール済みアプリの一覧・ラベル・アイコンを提供するリポジトリ。
 * パッケージの追加/削除/更新を監視して一覧を更新する。
 */
class AppRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pm: PackageManager = context.packageManager

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    /** アイコンキャッシュの世代。パッケージ変更で増え、表示中アイコンの再読込を促す。 */
    private val _iconVersion = MutableStateFlow(0)
    val iconVersion: StateFlow<Int> = _iconVersion

    private val refreshMutex = Mutex()

    private val iconCache = LruCache<String, ImageBitmap>(160)
    @Volatile
    private var labelIndex: Map<AppRef, String> = emptyMap()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.data?.schemeSpecificPart?.let { pkg ->
                synchronized(iconCache) {
                    iconCache.snapshot().keys
                        .filter { it.startsWith("$pkg/") }
                        .forEach { iconCache.remove(it) }
                }
                _iconVersion.value++
            }
            // アプリ更新中の REMOVED/ADDED(replacing) では再クエリしない。
            // 更新途中の一時的な非表示状態を検出してレイアウトを誤って掃除するのを防ぐ。
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (replacing && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
            refresh()
        }
    }

    fun startWatching() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(packageReceiver, filter)
        }
        refresh()
    }

    fun refresh() {
        scope.launch {
            // 直列化して、古いクエリ結果が新しい結果を上書きするレースを防ぐ
            refreshMutex.withLock {
                val list = queryLauncherActivities()
                labelIndex = list.associate { it.ref to it.label }
                _apps.value = list
            }
        }
    }

    /** パッケージが端末にインストールされているか(無効化中や更新中でも true)。 */
    fun isPackageInstalled(packageName: String): Boolean = try {
        pm.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun queryLauncherActivities(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val collator = Collator.getInstance(Locale.getDefault())
        return resolved
            .asSequence()
            .filter { it.activityInfo != null }
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                val packageName = it.activityInfo.packageName
                val label = it.loadLabel(pm)?.toString() ?: packageName
                AppInfo(
                    ref = AppRef(packageName, it.activityInfo.name),
                    label = label,
                    category = categorizeApp(
                        packageName = packageName,
                        label = label,
                        androidCategory = it.activityInfo.applicationInfo.category,
                    ),
                )
            }
            .distinctBy { it.ref }
            .sortedWith(compareBy(collator) { it.label })
            .toList()
    }

    fun labelOf(ref: AppRef): String =
        labelIndex[ref]
            ?: labelIndex.entries.firstOrNull { it.key.packageName == ref.packageName }?.value
            ?: ref.packageName.substringAfterLast('.')

    /** アイコンを読み込む(呼び出しスレッドで実行されるため、UI からは IO ディスパッチャで呼ぶこと)。 */
    fun iconOf(ref: AppRef): ImageBitmap? {
        val key = "${ref.packageName}/${ref.className}"
        synchronized(iconCache) { iconCache.get(key) }?.let { return it }
        val drawable: Drawable = try {
            pm.getActivityIcon(ComponentName(ref.packageName, ref.className))
        } catch (_: Exception) {
            try {
                pm.getApplicationIcon(ref.packageName)
            } catch (_: Exception) {
                return null
            }
        }
        return try {
            val bitmap = renderRoundedIcon(drawable).asImageBitmap()
            synchronized(iconCache) { iconCache.put(key, bitmap) }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * アイコンを iPhone 風の角丸矩形にレンダリングする。
     *
     * アダプティブアイコンは OS がそのまま描くと端末の形状マスク(Pixel は円形)が
     * 適用され、四隅が透明になってしまう。そこで前景+背景を自前で角丸矩形キャンバスに
     * 描き、円形マスクを回避して統一された角丸にする。
     * レガシー(非アダプティブ)アイコンは元のビットマップを角丸クリップで整える。
     */
    private fun renderRoundedIcon(drawable: Drawable): android.graphics.Bitmap {
        val size = ICON_SIZE_PX
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val radius = size * ICON_CORNER_RATIO
        val clip = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()),
                radius, radius, android.graphics.Path.Direction.CW,
            )
        }
        canvas.clipPath(clip)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            drawable is android.graphics.drawable.AdaptiveIconDrawable
        ) {
            // アダプティブアイコンの前景/背景は本来セーフゾーンより 1/9 ずつ外へはみ出す。
            // キャンバスより一回り大きい bounds を与えて中央のセーフゾーンが枠に収まるようにする。
            val inset = (size / 9f).toInt()
            val b = android.graphics.Rect(-inset, -inset, size + inset, size + inset)
            drawable.background?.apply { bounds = b; draw(canvas) }
            drawable.foreground?.apply { bounds = b; draw(canvas) }
        } else {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
        return bitmap
    }

    private companion object {
        const val ICON_SIZE_PX = 192
        // AppIcon 側の角丸比率(size*0.2237f)と揃える。
        const val ICON_CORNER_RATIO = 0.2237f
    }
}
