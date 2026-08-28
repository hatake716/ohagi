package io.github.hatake716.ohagi.util

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppRef
import kotlinx.coroutines.delay

object LaunchUtils {

    /**
     * アプリを通常(フルスクリーン)起動する。コンポーネントが消えていた場合は
     * パッケージ既定の起動インテントにフォールバック。
     *
     * @param options タップ矩形からの擬似ズーム遷移など、ActivityOptions.toBundle() を渡せる(任意)。
     */
    fun launch(context: Context, ref: AppRef, options: Bundle? = null) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(ref.packageName, ref.className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent, options)
        } catch (_: Exception) {
            val fallback = context.packageManager.getLaunchIntentForPackage(ref.packageName)
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(fallback, options)
                } catch (_: Exception) {
                    toastLaunchFailed(context)
                }
            } else {
                toastLaunchFailed(context)
            }
        }
    }

    /**
     * 2 アプリを OS の分割画面(split-screen)で起動する。これがタイリングの中核。
     *
     * 1 つ目を通常(フルスクリーン)起動した後、2 つ目を
     * FLAG_ACTIVITY_LAUNCH_ADJACENT で隣接起動すると、OS が縦画面なら上下・
     * 横画面なら左右に自動配置する(向きは OS が端末の縦横で決める)。
     *
     * - LAUNCH_ADJACENT は NEW_TASK との併用が必須。
     * - MULTIPLE_TASK を足すのは、2 つ目が既存タスクへ resume されて隣接配置が
     *   無視されるのを避けるため。
     * - Android 12L(API 32 / S_V2)以降はフルスクリーン→分割画面の移行が保証される。
     *   それ未満は best-effort のため、失敗し得る旨をトーストで案内する(起動自体は試みる)。
     * - 「1 つ目が既に起動済みか」の検出は権限的に不可能なので、毎回 first を起動し直す
     *   (アプリ側の launchMode に委ねる)。
     */
    suspend fun launchSplit(
        context: Context,
        first: AppRef,
        second: AppRef,
        firstOptions: Bundle? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
            // API 32 未満では分割画面移行が保証されない。順次起動は試みるが案内を出す。
            Toast.makeText(context, R.string.toast_split_unsupported, Toast.LENGTH_SHORT).show()
        }
        launch(context, first, firstOptions)
        delay(SPLIT_LAUNCH_DELAY_MS)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(second.packageName, second.className))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // 2 つ目の隣接起動に失敗しても、通常起動でリカバリする(フルスクリーンで開く)。
            launch(context, second)
        }
    }

    fun openAppInfo(context: Context, packageName: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toastLaunchFailed(context)
        }
    }

    fun requestUninstall(context: Context, packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toastLaunchFailed(context)
        }
    }

    /** デフォルトホームの選択を促す。Q 以降は RoleManager、それ以前はホーム設定画面。 */
    fun requestDefaultHome(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                activity.startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                    REQUEST_CODE_HOME_ROLE
                )
                return
            }
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            toastLaunchFailed(activity)
        }
    }

    private fun toastLaunchFailed(context: Context) {
        Toast.makeText(context, R.string.toast_launch_failed, Toast.LENGTH_SHORT).show()
    }

    // 1 つ目のタスクがフォアグラウンドに確立してから 2 つ目を隣接起動するための緩衝。
    // 1 つ目の RESUMED を厳密に待つ手段は無い(他アプリのライフサイクルは監視不可)ため
    // 固定遅延で近似する。値は体感上のバランス。cold start が遅い端末では隣接が不発になり得るが、
    // その場合は 2 つ目が通常起動でリカバリされる(下記 catch)。
    private const val SPLIT_LAUNCH_DELAY_MS = 450L
    private const val REQUEST_CODE_HOME_ROLE = 4101
}
