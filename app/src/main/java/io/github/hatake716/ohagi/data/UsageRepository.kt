package io.github.hatake716.ohagi.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** ohagiから起動した回数と最終起動時刻。端末外へは送信しない。 */
@Serializable
internal data class AppUsageEntry(
    val count: Int = 0,
    val lastLaunchMs: Long = 0L,
)

/** 既存端末のversion 1形式と互換な、ローカルの起動履歴。 */
@Serializable
internal data class UsageState(
    val entries: Map<String, AppUsageEntry> = emptyMap(),
    val version: Int = USAGE_STATE_VERSION,
)

/** 起動回数、最終起動時刻、アプリ参照の順で安定した優先リストを返す。 */
internal fun UsageState.rankedAppRefs(): List<AppRef> = entries
    .asSequence()
    .mapNotNull { (key, entry) -> usageKeyToAppRef(key)?.let { it to entry } }
    .sortedWith(
        compareByDescending<Pair<AppRef, AppUsageEntry>> { (_, entry) -> entry.count }
            .thenByDescending { (_, entry) -> entry.lastLaunchMs }
            .thenBy { (app, _) -> app.packageName }
            .thenBy { (app, _) -> app.className },
    )
    .map(Pair<AppRef, AppUsageEntry>::first)
    .toList()

/** 成功した起動1件を、過去データを保ったまま反映する純粋関数。 */
internal fun UsageState.withRecordedLaunch(app: AppRef, launchTimeMs: Long): UsageState {
    val key = app.toUsageKey()
    val previous = entries[key]
    val nextCount = when {
        previous == null -> 1
        previous.count == Int.MAX_VALUE -> Int.MAX_VALUE
        else -> (previous.count + 1).coerceAtLeast(1)
    }
    return copy(
        entries = entries +
            (key to AppUsageEntry(
                count = nextCount,
                lastLaunchMs = launchTimeMs.coerceAtLeast(0L),
            )),
        version = USAGE_STATE_VERSION,
    )
}

private fun AppRef.toUsageKey(): String = "$packageName/$className"

private fun usageKeyToAppRef(key: String): AppRef? {
    val separator = key.indexOf('/')
    if (separator <= 0 || separator == key.lastIndex) return null
    val packageName = key.substring(0, separator)
    val className = key.substring(separator + 1)
    if (packageName.isBlank() || className.isBlank()) return null
    return AppRef(packageName, className)
}

private val usageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private object UsageStateSerializer : Serializer<UsageState> {
    override val defaultValue = UsageState()

    override suspend fun readFrom(input: InputStream): UsageState = try {
        usageJson.decodeFromString<UsageState>(input.readBytes().decodeToString())
    } catch (error: SerializationException) {
        throw CorruptionException("usage.json の読み込みに失敗しました", error)
    }

    override suspend fun writeTo(t: UsageState, output: OutputStream) {
        @Suppress("BlockingMethodInNonBlockingContext")
        output.write(usageJson.encodeToString(UsageState.serializer(), t).encodeToByteArray())
    }
}

private val Context.usageDataStore: DataStore<UsageState> by dataStore(
    fileName = "usage.json",
    serializer = UsageStateSerializer,
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        UsageState()
    },
)

/**
 * ohagi自身が成功させたアプリ起動だけを記録する。
 *
 * UsageStats権限は使わず、更新は1本のIO consumerへ渡すためアプリ起動を待たせない。
 */
class UsageRepository(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = context.applicationContext.usageDataStore
    private val updates = Channel<(UsageState) -> UsageState>(Channel.UNLIMITED)

    val rankedApps: StateFlow<List<AppRef>> = store.data
        .map(UsageState::rankedAppRefs)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            for (transform in updates) {
                try {
                    store.updateData { current -> transform(current) }
                } catch (error: Exception) {
                    Log.w(TAG, "アプリ起動履歴の保存に失敗しました", error)
                }
            }
        }
    }

    fun recordLaunch(app: AppRef) {
        val launchTimeMs = System.currentTimeMillis()
        if (updates.trySend { state -> state.withRecordedLaunch(app, launchTimeMs) }.isFailure) {
            Log.w(TAG, "アプリ起動履歴の更新キューへ追加できませんでした")
        }
    }

    private companion object {
        const val TAG = "UsageRepository"
    }
}

private const val USAGE_STATE_VERSION = 1
