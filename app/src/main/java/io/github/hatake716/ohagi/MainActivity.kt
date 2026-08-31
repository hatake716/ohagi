package io.github.hatake716.ohagi

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.ui.HomeScreen
import io.github.hatake716.ohagi.ui.theme.OhagiTheme
import io.github.hatake716.ohagi.util.LaunchUtils
import io.github.hatake716.ohagi.util.SplitLaunchNotification
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    /** HOME ボタン再押下(onNewIntent)をホーム画面に伝えるイベント */
    private val homeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingWidgetProvider: AppWidgetProviderInfo? = null
    private var pendingAppLaunch: AppRef? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val app = pendingAppLaunch ?: return@registerForActivityResult
        pendingAppLaunch = null
        launchAppWithSplitNotification(app, canNotify = granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as OhagiApp).graph
        restorePendingWidget(savedInstanceState, graph)
        restorePendingAppLaunch(savedInstanceState)
        setContent {
            OhagiTheme {
                CompositionLocalProvider(LocalGraph provides graph) {
                    HomeScreen(
                        homeEvents = homeEvents,
                        onRequestWidget = ::requestWidget,
                        onLaunchApp = ::requestAppLaunch,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeEvents.tryEmit(Unit)
    }

    override fun onResume() {
        super.onResume()
        (application as OhagiApp).graph.appRepository.refreshIfStale()
    }

    override fun onStart() {
        super.onStart()
        (application as OhagiApp).graph.widgetHost.startListening()
    }

    override fun onStop() {
        (application as OhagiApp).graph.widgetHost.stopListening()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING_WIDGET_ID, pendingWidgetId)
        pendingWidgetProvider?.provider?.let { provider ->
            outState.putString(STATE_PENDING_WIDGET_PROVIDER, provider.flattenToString())
        }
        pendingAppLaunch?.let { app ->
            outState.putString(STATE_PENDING_APP_PACKAGE, app.packageName)
            outState.putString(STATE_PENDING_APP_CLASS, app.className)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_BIND_WIDGET -> {
                if (resultCode == Activity.RESULT_OK) continueWidgetConfiguration()
                else cancelPendingWidget()
            }

            REQUEST_CONFIGURE_WIDGET -> {
                if (resultCode == Activity.RESULT_OK) commitPendingWidget()
                else cancelPendingWidget()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestWidget(info: AppWidgetProviderInfo) {
        cancelPendingWidget()
        val graph = (application as OhagiApp).graph
        val controller = graph.widgetHost
        val appWidgetId = controller.allocateAppWidgetId()
        val placement = controller.placementFor(appWidgetId, info)
        val options = controller.bindingOptions(this, placement.heightDp)
        pendingWidgetId = appWidgetId
        pendingWidgetProvider = info

        if (controller.bindIfAllowed(appWidgetId, info, options)) {
            continueWidgetConfiguration()
            return
        }

        val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
        }
        try {
            startActivityForResult(bindIntent, REQUEST_BIND_WIDGET)
        } catch (_: ActivityNotFoundException) {
            cancelPendingWidget()
        }
    }

    @Suppress("DEPRECATION")
    private fun continueWidgetConfiguration() {
        val info = pendingWidgetProvider ?: run {
            cancelPendingWidget()
            return
        }
        val appWidgetId = pendingWidgetId
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val configurationOptional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure == null || configurationOptional) {
            commitPendingWidget()
            return
        }

        val controller = (application as OhagiApp).graph.widgetHost
        val placement = controller.placementFor(appWidgetId, info)
        try {
            controller.startConfiguration(
                activity = this,
                appWidgetId = appWidgetId,
                requestCode = REQUEST_CONFIGURE_WIDGET,
                options = controller.bindingOptions(this, placement.heightDp),
            )
        } catch (_: ActivityNotFoundException) {
            // 設定Activityを宣言しながら起動できないプロバイダーは、既定表示で残す。
            commitPendingWidget()
        }
    }

    private fun commitPendingWidget() {
        val info = pendingWidgetProvider ?: return
        val appWidgetId = pendingWidgetId
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val graph = (application as OhagiApp).graph
        graph.layoutRepository.addWidget(graph.widgetHost.placementFor(appWidgetId, info))
        clearPendingWidget()
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            (application as? OhagiApp)?.graph?.widgetHost?.deleteAppWidgetId(pendingWidgetId)
        }
        clearPendingWidget()
    }

    private fun clearPendingWidget() {
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        pendingWidgetProvider = null
    }

    /**
     * ホーム／Dock／フォルダ／Appライブラリからの通常起動を一元化する。
     * 通知権限が未決定なら、このユーザー操作の文脈で一度だけ確認してから起動する。
     */
    private fun requestAppLaunch(app: AppRef) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val permissionPrefs = getSharedPreferences(
                PREFS_NOTIFICATION_PERMISSION,
                MODE_PRIVATE,
            )
            if (permissionPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)) {
                launchAppWithSplitNotification(app, canNotify = false)
                return
            }
            permissionPrefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true) }
            pendingAppLaunch = app
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchAppWithSplitNotification(app, canNotify = true)
    }

    private fun launchAppWithSplitNotification(app: AppRef, canNotify: Boolean) {
        // 起動Intentを最優先でOSへ渡す。通知の組み立て/Binder呼び出しは、
        // 画面遷移開始後にアプリプロセスの直列IO workerで行う。
        if (!LaunchUtils.launch(this, app)) return
        val graph = (application as OhagiApp).graph
        graph.usageRepository.recordLaunch(app)
        if (canNotify) {
            val appLabel = graph.appRepository.labelOf(app)
            SplitLaunchNotification.postAsync(applicationContext, app, appLabel)
        }
    }

    private fun restorePendingWidget(savedState: Bundle?, graph: Graph) {
        val appWidgetId = savedState?.getInt(
            STATE_PENDING_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val component = savedState
            ?.getString(STATE_PENDING_WIDGET_PROVIDER)
            ?.let(ComponentName::unflattenFromString)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || component == null) return
        pendingWidgetId = appWidgetId
        pendingWidgetProvider = graph.widgetHost.installedProviders()
            .firstOrNull { it.provider == component }
        if (pendingWidgetProvider == null) cancelPendingWidget()
    }

    private fun restorePendingAppLaunch(savedState: Bundle?) {
        val packageName = savedState?.getString(STATE_PENDING_APP_PACKAGE) ?: return
        val className = savedState.getString(STATE_PENDING_APP_CLASS) ?: return
        pendingAppLaunch = AppRef(packageName, className)
    }

    private companion object {
        const val REQUEST_BIND_WIDGET = 7160
        const val REQUEST_CONFIGURE_WIDGET = 7161
        const val STATE_PENDING_WIDGET_ID = "pending_widget_id"
        const val STATE_PENDING_WIDGET_PROVIDER = "pending_widget_provider"
        const val STATE_PENDING_APP_PACKAGE = "pending_app_package"
        const val STATE_PENDING_APP_CLASS = "pending_app_class"
        const val PREFS_NOTIFICATION_PERMISSION = "notification_permission"
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "asked"
    }
}
