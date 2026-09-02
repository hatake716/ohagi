package io.github.hatake716.ohagi.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.data.LayoutState

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

    /** uri 文字列キーのサムネイルキャッシュ。ホーム1画面ぶんに十分な数。 */
    private val thumbnailCache = LruCache<String, ImageBitmap>(48)

    /** サムネイル表示に対応する種類か(画像/動画/PDF)。それ以外は種類別アイコンで表示する。 */
    fun supportsThumbnail(mimeType: String?): Boolean = when (kindOf(mimeType)) {
        PinKind.IMAGE, PinKind.VIDEO, PinKind.PDF -> true
        else -> false
    }

    /** キャッシュ済みサムネイルの同期取得(セル再利用時のちらつき防止用)。 */
    fun cachedThumbnailOf(uriString: String): ImageBitmap? = thumbnailCache.get(uriString)

    /**
     * ピンのサムネイルを読み込む。IO を伴うため呼び出し側で IO ディスパッチャを使うこと。
     * 実体消失・非対応プロバイダ等は null(呼び出し側が種類別アイコンへフォールバック)。
     */
    fun thumbnailOf(
        context: Context,
        uriString: String,
        mimeType: String?,
        sizePx: Int,
    ): ImageBitmap? {
        if (!supportsThumbnail(mimeType)) return null
        thumbnailCache.get(uriString)?.let { return it }
        val uri = Uri.parse(uriString)
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            } else {
                @Suppress("DEPRECATION")
                DocumentsContract.getDocumentThumbnail(
                    context.contentResolver, uri, Point(sizePx, sizePx), null,
                )
            }
        }.getOrNull() ?: return null
        val image = bitmap.asImageBitmap()
        thumbnailCache.put(uriString, image)
        return image
    }

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
