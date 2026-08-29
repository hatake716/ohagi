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

object LaunchUtils {

    /**
     * アプリを通常(フルスクリーン)起動する。コンポーネントが消えていた場合は
     * パッケージ既定の起動インテントにフォールバック。
     *
     * @param options タップ矩形からの擬似ズーム遷移など、ActivityOptions.toBundle() を渡せる(任意)。
     */
    fun launch(context: Context, ref: AppRef, options: Bundle? = null): Boolean {
        val intent = launcherIntent(ref)
        try {
            context.startActivity(intent, options)
            return true
        } catch (_: Exception) {
            val fallback = context.packageManager.getLaunchIntentForPackage(ref.packageName)
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(fallback, options)
                    return true
                } catch (_: Exception) {
                    toastLaunchFailed(context)
                }
            } else {
                toastLaunchFailed(context)
            }
        }
        return false
    }

    /** ランチャーから通常起動するIntent。 */
    fun launcherIntent(ref: AppRef): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(ref.packageName, ref.className))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** 呼び出し元Activityと同じタスクへ1つ目を積み、分割側の起点を維持するIntent。 */
    fun inTaskLaunchIntent(ref: AppRef): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(ref.packageName, ref.className))

    /** 通知から開いた選択画面を起点に、2つ目を隣接起動するIntent。 */
    fun adjacentLaunchIntent(ref: AppRef): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(ref.packageName, ref.className))
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        )

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

    private const val REQUEST_CODE_HOME_ROLE = 4101
}
