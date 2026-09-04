package io.github.hatake716.ohagi.data

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** ドロワー表示用のアプリ情報 */
data class AppInfo(
    val ref: AppRef,
    val label: String,
    val category: AppCategory,
)

/** 画面表示より前に用意するアイコンと、その実表示サイズ。 */
data class AppIconRequest(
    val ref: AppRef,
    val requestedSizePx: Int,
)

private data class CachedIcon(
    val image: ImageBitmap,
    val sizeKb: Int,
)

/**
 * インストール済みアプリの一覧・ラベル・アイコンを提供するリポジトリ。
 * パッケージの追加/削除/更新を監視して一覧を更新する。
 */
class AppRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pm: PackageManager = context.packageManager
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    /** アイコンキャッシュの世代。パッケージ変更で増え、表示中アイコンの再読込を促す。 */
    private val _iconVersion = mutableIntStateOf(0)
    val iconVersion: Int get() = _iconVersion.intValue

    val isLowRamDevice: Boolean =
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    private val iconCacheMaxKb = if (isLowRamDevice) {
        LOW_RAM_ICON_CACHE_KB
    } else {
        DEFAULT_ICON_CACHE_KB
    }
    // PackageManagerのDrawable展開とBitmap生成を大量並列にすると、Pager操作中に
    // CPU/GPU準備が集中する。端末性能に応じて1〜2本へ絞り、UIスレッドを優先する。
    @OptIn(ExperimentalCoroutinesApi::class)
    private val iconLoadDispatcher = Dispatchers.IO.limitedParallelism(
        if (isLowRamDevice) LOW_RAM_ICON_LOAD_PARALLELISM else ICON_LOAD_PARALLELISM,
    )
    private val iconCache = object : LruCache<String, CachedIcon>(iconCacheMaxKb) {
        override fun sizeOf(key: String, value: CachedIcon): Int = value.sizeKb
    }
    // Guarded by iconCache. A decode already in flight must not refill a cache
    // that the OS has just asked us to trim, or restore an invalidated icon.
    private var iconCacheEpoch = 0L
    private val iconLoadLocks = ConcurrentHashMap<String, Any>()
    @Volatile
    private var labelIndex: Map<AppRef, String> = emptyMap()
    @Volatile
    private var packageLabelIndex: Map<String, String> = emptyMap()
    @Volatile
    private var lastRefreshElapsedMs = 0L
    private var watchingPackages = false

    init {
        // 連続するonResumeやパッケージ通知を1件に畳み、重いPackageManager走査を直列化する。
        scope.launch {
            for (ignored in refreshRequests) {
                runCatching(::queryLauncherActivities)
                    .onSuccess { list ->
                        labelIndex = list.associate { it.ref to it.label }
                        packageLabelIndex = list
                            .distinctBy { it.ref.packageName }
                            .associate { it.ref.packageName to it.label }
                        _apps.value = list
                        lastRefreshElapsedMs = SystemClock.elapsedRealtime()
                    }
                    .onFailure { error ->
                        Log.w(TAG, "ランチャーアプリ一覧の更新に失敗しました", error)
                    }
            }
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.data?.schemeSpecificPart?.let { pkg ->
                synchronized(iconCache) {
                    iconCacheEpoch++
                    iconCache.snapshot().keys
                        .filter { it.startsWith("$pkg/") }
                        .forEach { iconCache.remove(it) }
                }
                _iconVersion.intValue++
            }
            // アプリ更新中の REMOVED/ADDED(replacing) では再クエリしない。
            // 更新途中の一時的な非表示状態を検出してレイアウトを誤って掃除するのを防ぐ。
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (replacing && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
            refresh()
        }
    }

    fun startWatching() {
        if (watchingPackages) return
        watchingPackages = true
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
        refreshRequests.trySend(Unit)
    }

    /** アプリから戻るたびの全件走査を避けつつ、長時間表示後は一覧を再同期する。 */
    fun refreshIfStale(maxAgeMs: Long = FOREGROUND_REFRESH_INTERVAL_MS) {
        val last = lastRefreshElapsedMs
        if (last == 0L || SystemClock.elapsedRealtime() - last >= maxAgeMs) {
            refresh()
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
            ?: packageLabelIndex[ref.packageName]
            ?: ref.packageName.substringAfterLast('.')

    /** LRUにある画像だけを同期取得する。ヒット時はCompose側のCoroutine生成も不要になる。 */
    fun cachedIconOf(ref: AppRef, requestedSizePx: Int = LARGE_ICON_SIZE_PX): ImageBitmap? {
        val renderSizePx = iconRenderSize(requestedSizePx)
        val key = iconCacheKey(ref, renderSizePx)
        return synchronized(iconCache) { iconCache.get(key)?.image }
    }

    /** アイコン生成を制限付きIO dispatcherへ集約し、Pager中のCPU飽和を防ぐ。 */
    suspend fun loadIcon(
        ref: AppRef,
        requestedSizePx: Int = LARGE_ICON_SIZE_PX,
    ): ImageBitmap? = withContext(iconLoadDispatcher) {
        iconOf(ref, requestedSizePx)
    }

    /** Appライブラリなどの初回表示に必要な画像を、画面外で順番に準備する。 */
    suspend fun prefetchIcons(requests: List<AppIconRequest>) {
        withContext(iconLoadDispatcher) {
            requests.forEach { request ->
                currentCoroutineContext().ensureActive()
                iconOf(request.ref, request.requestedSizePx)
            }
        }
    }

    /** アイコンを読み込む(呼び出しスレッドで実行されるため、UI からは IO ディスパッチャで呼ぶこと)。 */
    fun iconOf(ref: AppRef, requestedSizePx: Int = LARGE_ICON_SIZE_PX): ImageBitmap? {
        val renderSizePx = iconRenderSize(requestedSizePx)
        val key = iconCacheKey(ref, renderSizePx)
        synchronized(iconCache) { iconCache.get(key)?.image }?.let { return it }

        // 同じアイコンは表示本体・D&D装飾・フォルダプレビューから同時要求される。
        // key単位で二重生成を止め、別アプリの読み込みは並行できるようにする。
        val loadLock = iconLoadLocks.computeIfAbsent(key) { Any() }
        return try {
            synchronized(loadLock) load@{
                val epochAtStart = synchronized(iconCache) {
                    iconCache.get(key)?.image?.let { return@load it }
                    iconCacheEpoch
                }
                val drawable: Drawable = try {
                    pm.getActivityIcon(ComponentName(ref.packageName, ref.className))
                } catch (_: Exception) {
                    try {
                        pm.getApplicationIcon(ref.packageName)
                    } catch (_: Exception) {
                        return@load null
                    }
                }
                try {
                    val softwareBitmap = renderRoundedIcon(drawable, renderSizePx)
                    // 表示専用アイコンはGPU Bitmapへ移してから公開する。初回draw時に
                    // UIスレッドへ集中していたpalette計算とtexture uploadを前倒しし、
                    // software pixels + GPU textureの二重保持も避ける。
                    val displayBitmap = runCatching {
                        softwareBitmap.copy(Bitmap.Config.HARDWARE, false)
                    }.getOrNull()
                    val bitmap = if (displayBitmap != null) {
                        softwareBitmap.recycle()
                        displayBitmap.asImageBitmap()
                    } else {
                        // 端末固有理由でHARDWARE化できなくても、RenderThreadへの
                        // 非同期uploadだけはアイコン読込スレッドから先行させる。
                        softwareBitmap.prepareToDraw()
                        softwareBitmap.asImageBitmap()
                    }
                    // 読み込み中に対象パッケージ群が更新された場合は古い画像を保持しない。
                    synchronized(iconCache) {
                        if (iconCacheEpoch == epochAtStart) {
                            iconCache.put(
                                key,
                                CachedIcon(
                                    image = bitmap,
                                    sizeKb = (renderSizePx * renderSizePx * 4 + 1023) / 1024,
                                ),
                            )
                        }
                    }
                    bitmap
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            iconLoadLocks.remove(key, loadLock)
        }
    }

    /** システムのメモリ圧迫通知に合わせ、再生成可能なBitmapだけを段階的に解放する。 */
    @Suppress("DEPRECATION")
    fun trimMemory(level: Int) {
        val targetKb = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 0
            // UIが隠れただけなら、次のHOME復帰に必要な分は残す。実際の
            // background/critical圧迫通知では従来どおり即座に縮小・解放する。
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                if (isLowRamDevice) LOW_RAM_HIDDEN_ICON_CACHE_KB else HIDDEN_ICON_CACHE_KB
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> CRITICAL_ICON_CACHE_KB
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> RUNNING_LOW_ICON_CACHE_KB
            else -> return
        }.coerceAtMost(iconCacheMaxKb)
        synchronized(iconCache) {
            iconCacheEpoch++
            if (targetKb == 0) iconCache.evictAll() else iconCache.trimToSize(targetKb)
        }
    }

    fun clearIconCache() {
        synchronized(iconCache) {
            iconCacheEpoch++
            iconCache.evictAll()
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
    private fun renderRoundedIcon(
        drawable: Drawable,
        size: Int,
    ): android.graphics.Bitmap {
        val bitmap = createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val radius = size * ICON_CORNER_RATIO
        val clip = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()),
                radius, radius, android.graphics.Path.Direction.CW,
            )
        }
        canvas.clipPath(clip)

        if (drawable is android.graphics.drawable.AdaptiveIconDrawable) {
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

    private fun iconCacheKey(ref: AppRef, renderSizePx: Int): String =
        "${ref.packageName}/${ref.className}@$renderSizePx"

    private companion object {
        const val LARGE_ICON_SIZE_PX = 192
        const val DEFAULT_ICON_CACHE_KB = 6 * 1024
        const val LOW_RAM_ICON_CACHE_KB = 3 * 1024
        const val RUNNING_LOW_ICON_CACHE_KB = 2 * 1024
        const val HIDDEN_ICON_CACHE_KB = 3 * 1024
        const val LOW_RAM_HIDDEN_ICON_CACHE_KB = 1 * 1024
        const val CRITICAL_ICON_CACHE_KB = 1 * 1024
        const val ICON_LOAD_PARALLELISM = 2
        const val LOW_RAM_ICON_LOAD_PARALLELISM = 1
        const val FOREGROUND_REFRESH_INTERVAL_MS = 15_000L
        // AppIcon 側の角丸比率(size*0.2237f)と揃える。
        const val ICON_CORNER_RATIO = 0.2237f
        const val TAG = "AppRepository"
    }
}
