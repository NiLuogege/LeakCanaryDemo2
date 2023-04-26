package leakcanary.internal

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.AppWatcher

/**
 * Content providers are loaded before the application class is created. [AppWatcherInstaller] is
 * used to install [leakcanary.AppWatcher] on application start.
 *
 * LeakCanary 的自启动器，这是一个ContentProvider 先于application 启动
 *
 * sealed 关键字标识密封类， 标识 AppWatcherInstaller 这个对象就只有这个文件中声明的 这几种子类
 */
internal sealed class AppWatcherInstaller : ContentProvider() {

    /**
     * [MainProcess] automatically sets up the LeakCanary code that runs in the main app process.
     *
     * 这个是一般使用时 APP 使用的 ContentProvider
     */
    internal class MainProcess : AppWatcherInstaller()

    /**
     * When using the `leakcanary-android-process` artifact instead of `leakcanary-android`,
     * [LeakCanaryProcess] automatically sets up the LeakCanary code
     */
    internal class LeakCanaryProcess : AppWatcherInstaller() {
        override fun onCreate(): Boolean {
            super.onCreate()
            AppWatcher.config = AppWatcher.config.copy(enabled = false)
            return true
        }
    }

    override fun onCreate(): Boolean {
        val application = context!!.applicationContext as Application
        //进行初始化
        InternalAppWatcher.install(application)
        return true
    }

    override fun query(
            uri: Uri,
            strings: Array<String>?,
            s: String?,
            strings1: Array<String>?,
            s1: String?
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(
            uri: Uri,
            contentValues: ContentValues?
    ): Uri? {
        return null
    }

    override fun delete(
            uri: Uri,
            s: String?,
            strings: Array<String>?
    ): Int {
        return 0
    }

    override fun update(
            uri: Uri,
            contentValues: ContentValues?,
            s: String?,
            strings: Array<String>?
    ): Int {
        return 0
    }
}