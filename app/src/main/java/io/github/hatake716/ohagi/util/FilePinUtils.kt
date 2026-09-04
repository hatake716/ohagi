package io.github.hatake716.ohagi.util

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Size
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.data.LayoutState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ホームへピン留めしたファイル/フォルダ(SAF)の取り扱い。
 * 実体のファイル操作(削除・コピー・実体リネーム)は一切行わない。
 * 全ファイルアクセス権限は使わず、SAF の persistable URI 許可だけで完結させる。
 */
object FilePinUtils {

    /** dango(Finder 風ファイラー)が入っていればフォルダはそちらで開く。 */
    private const val DANGO_PACKAGE = "io.github.hatake716.dango"

    /**
     * 永続許可の追加上限の手前で警告するしきい値。
     * OS 上限は Android 11+ で 512 / それ以前で 128。minSdk 26 を考慮し保守的に取る。
     */
    private const val PERSISTED_URI_WARN_THRESHOLD = 120

    /** ファイルピンのアイコン用の大分類。 */
    enum class PinKind { IMAGE, VIDEO, AUDIO, PDF, DOCUMENT, ARCHIVE, OTHER }

    fun kindOf(mimeType: String?): PinKind {
        val mime = mimeType.orEmpty()
        return when {
            mime.startsWith("image/") -> PinKind.IMAGE
            mime.startsWith("video/") -> PinKind.VIDEO
            mime.startsWith("audio/") -> PinKind.AUDIO
            mime == "application/pdf" -> PinKind.PDF
            mime.startsWith("text/") ||
                mime.contains("document") ||
                mime.contains("msword") ||
                mime.contains("spreadsheet") ||
                mime.contains("presentation") -> PinKind.DOCUMENT
            mime.contains("zip") ||
                mime.contains("compressed") ||
                mime.contains("archive") ||
                mime == "application/x-tar" -> PinKind.ARCHIVE
            else -> PinKind.OTHER
        }
    }

    /**
     * ACTION_OPEN_DOCUMENT の結果からピンを作る。
     * 永続許可の取得に失敗したらピン化しない(再起動後に開けなくなるため)。
     */
    fun describeDocument(context: Context, uri: Uri): HomeItem.HomeFile? {
        if (!takePersistableRead(context, uri)) return null
        val name = queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: context.getString(R.string.pin_unknown_file)
        return HomeItem.HomeFile(
            uri = uri.toString(),
            displayName = name,
            mimeType = context.contentResolver.getType(uri),
        )
    }

    /** ACTION_OPEN_DOCUMENT_TREE の結果からフォルダピンを作る。 */
    fun describeTree(context: Context, treeUri: Uri): HomeItem.HomeDirectory? {
        if (!takePersistableRead(context, treeUri)) return null
        val docUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrNull()
        val name = docUri?.let { queryDisplayName(context, it) }
            ?: treeUri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: context.getString(R.string.pin_unknown_folder)
        return HomeItem.HomeDirectory(
            treeUri = treeUri.toString(),
            displayName = name,
        )
    }

    /**
     * ピン解除時、同じ URI を参照する他のピンが残らないなら永続許可を返す。
     * (許可は URI 単位で共有されるため、最後の 1 つを外すときだけ解放する)
     *
     * [stateBeforeRemoval] には**解除前**のレイアウトを渡す(解除の DataStore 反映は
     * 非同期のため)。解除対象自身の 1 件を含んだ個数で判定する。
     */
    fun releasePinPermissionIfUnused(
        context: Context,
        stateBeforeRemoval: LayoutState,
        uriString: String,
    ) {
        val pinnedCount = stateBeforeRemoval.home.count { item ->
            when (item) {
                is HomeItem.HomeFile -> item.uri == uriString
                is HomeItem.HomeDirectory -> item.treeUri == uriString
                else -> false
            }
        }
        if (pinnedCount > 1) return // 解除後も他のセルに残る
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /** ファイルを既定アプリで開く。 */
    fun openFile(context: Context, pin: HomeItem.HomeFile) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(pin.uri), pin.mimeType ?: "*/*")
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        startOrToastMissing(context, intent)
    }

    /** ファイルをアプリ選択(chooser)で開く。 */
    fun openFileWithChooser(context: Context, pin: HomeItem.HomeFile) {
        val target = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(pin.uri), pin.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(target, pin.displayName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startOrToastMissing(context, chooser)
    }

    /** フォルダを開く。dango が入っていれば dango、無ければ汎用の Files/DocumentsUI。 */
    fun openDirectory(context: Context, pin: HomeItem.HomeDirectory) {
        val uri = Uri.parse(pin.treeUri)
        val base = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        val viaDango = Intent(base).setPackage(DANGO_PACKAGE)
        if (context.packageManager.resolveActivity(viaDango, 0) != null) {
            try {
                context.startActivity(viaDango)
                return
            } catch (_: Exception) {
                // dango 側の不調時は汎用へフォールバック
            }
        }
        startOrToastMissing(context, base)
    }

    // ---- サムネイル(M1) ----

    @Volatile
    private var thumbnails: FileThumbnails? = null

    private fun thumbnails(context: Context): FileThumbnails = thumbnails ?: synchronized(this) {
        thumbnails ?: FileThumbnails(
            lowRam = context.applicationContext
                .getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
        ).also { thumbnails = it }
    }

    /** サムネイル表示に対応する種類か(画像/動画/PDF)。それ以外は種類別アイコンで表示する。 */
    fun supportsThumbnail(mimeType: String?): Boolean = when (kindOf(mimeType)) {
        PinKind.IMAGE, PinKind.VIDEO, PinKind.PDF -> true
        else -> false
    }

    /** キャッシュ済みサムネイルの同期取得(セル再利用時のちらつき防止用)。 */
    fun cachedThumbnailOf(context: Context, uriString: String, sizePx: Int): ImageBitmap? =
        thumbnails(context).cache.get(ThumbnailKey(uriString, sizePx.coerceAtLeast(1)))?.image

    /**
     * 同じURI/表示サイズの読込を共有し、providerのIOを1〜2本に制限する。
     * 最後の表示側がキャンセルされると、providerへもキャンセルを伝える。
     * 実体消失・非対応プロバイダ等は null(呼び出し側が種類別アイコンへフォールバック)。
     */
    suspend fun thumbnailOf(
        context: Context,
        uriString: String,
        mimeType: String?,
        sizePx: Int,
    ): ImageBitmap? {
        if (!supportsThumbnail(mimeType)) return null
        val appContext = context.applicationContext
        val key = ThumbnailKey(uriString, sizePx.coerceAtLeast(1))
        return thumbnails(appContext).cache.getOrLoad(key) {
            var bitmap = loadProviderThumbnail(appContext, key) ?: return@getOrLoad null
            try {
                currentCoroutineContext().ensureActive()
                val (width, height) = thumbnailDimensions(bitmap.width, bitmap.height, key.sizePx)
                if (bitmap.width != width || bitmap.height != height) {
                    // 表示に十分な解像度と縦横比を保ち、providerの過大な画像を保持しない。
                    val scaled = bitmap.scale(width, height, true)
                    if (scaled !== bitmap) {
                        bitmap.recycle()
                        bitmap = scaled
                    }
                }
                currentCoroutineContext().ensureActive()
                bitmap.prepareToDraw()
                CachedThumbnail(bitmap.asImageBitmap(), bitmap.allocationByteCount.toLong())
            } catch (cancelled: CancellationException) {
                // まだcacheやComposeへ公開していないBitmapだけを解放する。
                bitmap.recycle()
                throw cancelled
            } catch (_: Exception) {
                bitmap.recycle()
                null
            }
        }?.image
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun loadProviderThumbnail(context: Context, key: ThumbnailKey): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            try {
                val uri = Uri.parse(key.uri)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(
                        uri, Size(key.sizePx, key.sizePx), cancellationSignal,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    DocumentsContract.getDocumentThumbnail(
                        context.contentResolver, uri, Point(key.sizePx, key.sizePx), cancellationSignal,
                    )
                }
                continuation.resume(bitmap) { bitmap?.recycle() }
            } catch (cancelled: CancellationException) {
                continuation.cancel(cancelled)
            } catch (_: Exception) {
                // 失敗結果はキャッシュしない。再訪・SD再装着後は同じURIを再試行できる。
                if (continuation.isActive) continuation.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    fun trimThumbnailMemory(level: Int) {
        val current = thumbnails ?: return
        val target = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 0L
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                if (current.lowRam) 512L * 1024 else 2L * 1024 * 1024
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 512L * 1024
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 1024L * 1024
            else -> return
        }
        current.cache.trimTo(target)
    }

    fun clearThumbnailCache() {
        thumbnails?.cache?.trimTo(0)
    }

    private class FileThumbnails(val lowRam: Boolean) {
        val cache = ThumbnailCache<ThumbnailKey, CachedThumbnail>(
            maxBytes = (if (lowRam) 2L else 4L) * 1024 * 1024,
            maxParallelLoads = if (lowRam) 1 else 2,
            sizeOf = CachedThumbnail::bytes,
        )
    }

    private data class CachedThumbnail(val image: ImageBitmap, val bytes: Long)

    /** 永続許可数が上限に近ければ警告トーストを出す(追加自体は妨げない)。 */
    fun warnIfNearPermissionLimit(context: Context) {
        val count = context.contentResolver.persistedUriPermissions.size
        if (count >= PERSISTED_URI_WARN_THRESHOLD) {
            Toast.makeText(context, R.string.toast_pin_limit_near, Toast.LENGTH_LONG).show()
        }
    }

    private fun takePersistableRead(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.toast_pin_failed, Toast.LENGTH_SHORT).show()
        false
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun startOrToastMissing(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_pin_open_failed, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            // 実体の削除・SD カード取り外し・許可失効など。ピンの自動掃除はしない(仕様)。
            Toast.makeText(context, R.string.toast_pin_missing, Toast.LENGTH_LONG).show()
        }
    }
}
