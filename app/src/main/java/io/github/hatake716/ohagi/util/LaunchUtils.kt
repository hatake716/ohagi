package io.github.hatake716.ohagi.util

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppRef
import kotlinx.coroutines.delay

object LaunchUtils {

    /** アプリを通常起動する。コンポーネントが消えていた場合はパッケージ既定の起動インテントにフォールバック。 */
    fun launch(context: Context, ref: AppRef) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(ref.packageName, ref.className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = context.packageManager.getLaunchIntentForPackage(ref.packageName)
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(fallback)
                } catch (_: Exception) {
                    toastLaunchFailed(context)
                }
            } else {
                toastLaunchFailed(context)
            }
        }
    }

    /**
     * 2 アプリを分割画面で起動する。
     * 1 つ目を通常起動した後、2 つ目を FLAG_ACTIVITY_LAUNCH_ADJACENT で隣接起動すると、
     * OS が縦画面なら上下、横画面なら左右に自動配置する。
     * (一部メーカー ROM では動作しない場合がある)
     */
    suspend fun launchSplit(context: Context, first: AppRef, second: AppRef) {
        launch(context, first)
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
            Toast.makeText(context, R.string.toast_split_unsupported, Toast.LENGTH_SHORT).show()
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

    fun openWallpaperPicker(context: Context) {
        val intent = Intent.createChooser(
            Intent(Intent.ACTION_SET_WALLPAPER),
            context.getString(R.string.menu_change_wallpaper)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private const val SPLIT_LAUNCH_DELAY_MS = 450L
    private const val REQUEST_CODE_HOME_ROLE = 4101
}
