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
import java.text.Collator
import java.util.Locale

/** ドロワー表示用のアプリ情報 */
data class AppInfo(
    val ref: AppRef,
    val label: String,
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
            }
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
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(packageReceiver, filter)
        }
        refresh()
    }

    fun refresh() {
        scope.launch {
            val list = queryLauncherActivities()
            labelIndex = list.associate { it.ref to it.label }
            _apps.value = list
        }
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
                AppInfo(
                    ref = AppRef(it.activityInfo.packageName, it.activityInfo.name),
                    label = it.loadLabel(pm)?.toString()
                        ?: it.activityInfo.packageName,
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
            val bitmap = drawable.toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX).asImageBitmap()
            synchronized(iconCache) { iconCache.put(key, bitmap) }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val ICON_SIZE_PX = 192
    }
}
