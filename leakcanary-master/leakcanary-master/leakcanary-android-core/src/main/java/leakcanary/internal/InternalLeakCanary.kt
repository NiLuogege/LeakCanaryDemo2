package leakcanary.internal

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import android.content.pm.ShortcutInfo.Builder
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.HandlerThread
import com.squareup.leakcanary.core.BuildConfig
import com.squareup.leakcanary.core.R
import leakcanary.AppWatcher
import leakcanary.GcTrigger
import leakcanary.LeakCanary
import leakcanary.LeakCanary.Config
import leakcanary.OnHeapAnalyzedListener
import leakcanary.OnObjectRetainedListener
import leakcanary.internal.activity.LeakActivity
import shark.SharkLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

//因为这玩意儿 继承了一个 (Application) -> Unit 方法,所以需要重写一个 invoke(application: Application) 方法
// 当在InternalAppWatcher 中调用 onAppWatcherInstalled(application) 时就会走到 这个 invoke 方法中
internal object InternalLeakCanary : (Application) -> Unit, OnObjectRetainedListener {

    private const val DYNAMIC_SHORTCUT_ID = "com.squareup.leakcanary.dynamic_shortcut"

    private lateinit var heapDumpTrigger: HeapDumpTrigger

    lateinit var application: Application

    // BuildConfig.LIBRARY_VERSION is stripped so this static var is how we keep it around to find
    // it later when parsing the heap dump.
    @Suppress("unused")
    @JvmStatic
    private var version = BuildConfig.LIBRARY_VERSION

    @Volatile
    var applicationVisible = false
        private set

    private val testClassName by lazy {
        application.getString(R.string.leak_canary_test_class_name)
    }

    private val isRunningTests by lazy {
        try {
            Class.forName(testClassName)
            true
        } catch (e: Exception) {
            false
        }
    }

    //堆转储文件目录帮助类
    val leakDirectoryProvider: LeakDirectoryProvider by lazy {
        LeakDirectoryProvider(application, {
            LeakCanary.config.maxStoredHeapDumps
        }, {
            LeakCanary.config.requestWriteExternalStoragePermission
        })
    }

    val leakDisplayActivityIntent: Intent
        get() = LeakActivity.createIntent(application)

    val noInstallConfig: Config
        get() = Config(
                dumpHeap = false,
                referenceMatchers = emptyList(),
                objectInspectors = emptyList(),
                onHeapAnalyzedListener = OnHeapAnalyzedListener {}
        )

    //会在 InternalAppWatcher 的 install 方法中会被调用
    override fun invoke(application: Application) {
        this.application = application

        //添加对象被保留的监听
        AppWatcher.objectWatcher.addOnObjectRetainedListener(this)

        //创建堆转储文件生成器
        val heapDumper = AndroidHeapDumper(application, leakDirectoryProvider)

        //gc 触发器
        val gcTrigger = GcTrigger.Default

        val configProvider = { LeakCanary.config }
        //创建异步线程
        val handlerThread = HandlerThread(LEAK_CANARY_THREAD_NAME)
        handlerThread.start()
        //异步线程用于后台服务
        val backgroundHandler = Handler(handlerThread.looper)

        //初始化HeapDumpTrigger
        heapDumpTrigger = HeapDumpTrigger(
                application, backgroundHandler, AppWatcher.objectWatcher, gcTrigger, heapDumper,
                configProvider
        )
        application.registerVisibilityListener { applicationVisible ->
            //当APP可见性切换时就会回调 到这个方法
            this.applicationVisible = applicationVisible
            heapDumpTrigger.onApplicationVisibilityChanged(applicationVisible)
        }
        //创建桌面快捷图标 点击直接进入LeakActivity
        addDynamicShortcut(application)

        //单元测试环境 禁用堆转储功能
        disableDumpHeapInTests()
    }

    /**
     * 单元测试环境 禁用堆转储功能
     */
    private fun disableDumpHeapInTests() {
        // This is called before Application.onCreate(), so if the class is loaded through a secondary
        // dex it might not be available yet.
        Handler().post {
            if (isRunningTests) {
                SharkLog.d { "$testClassName detected in classpath, app is running tests => disabling heap dumping & analysis" }
                LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
            }
        }
    }

    /**
     * 创建桌面快捷图标 点击直接进入LeakActivity
     */
    @Suppress("ReturnCount")
    private fun addDynamicShortcut(application: Application) {
        if (VERSION.SDK_INT < VERSION_CODES.N_MR1) {
            return
        }
        if (!application.resources.getBoolean(R.bool.leak_canary_add_dynamic_shortcut)) {
            return
        }
        if (VERSION.SDK_INT >= VERSION_CODES.O && application.packageManager.isInstantApp) {
            // Instant Apps don't have access to ShortcutManager
            return
        }

        val shortcutManager = application.getSystemService(ShortcutManager::class.java)!!
        val dynamicShortcuts = shortcutManager.dynamicShortcuts

        val shortcutInstalled =
                dynamicShortcuts.any { shortcut -> shortcut.id == DYNAMIC_SHORTCUT_ID }

        if (shortcutInstalled) {
            return
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        mainIntent.setPackage(application.packageName)
        val activities = application.packageManager.queryIntentActivities(mainIntent, 0)
                .filter {
                    it.activityInfo.name != "leakcanary.internal.activity.LeakLauncherActivity"
                }

        if (activities.isEmpty()) {
            return
        }

        val firstMainActivity = activities.first()
                .activityInfo

        // Displayed on long tap on app icon
        val longLabel: String
        // Label when dropping shortcut to launcher
        val shortLabel: String

        val leakActivityLabel = application.getString(R.string.leak_canary_shortcut_label)

        if (activities.isEmpty()) {
            longLabel = leakActivityLabel
            shortLabel = leakActivityLabel
        } else {
            val firstLauncherActivityLabel = if (firstMainActivity.labelRes != 0) {
                application.getString(firstMainActivity.labelRes)
            } else {
                application.packageManager.getApplicationLabel(application.applicationInfo)
            }
            val fullLengthLabel = "$firstLauncherActivityLabel $leakActivityLabel"
            // short label should be under 10 and long label under 25
            if (fullLengthLabel.length > 10) {
                if (fullLengthLabel.length <= 25) {
                    longLabel = fullLengthLabel
                    shortLabel = leakActivityLabel
                } else {
                    longLabel = leakActivityLabel
                    shortLabel = leakActivityLabel
                }
            } else {
                longLabel = fullLengthLabel
                shortLabel = fullLengthLabel
            }
        }

        val componentName = ComponentName(firstMainActivity.packageName, firstMainActivity.name)

        val shortcutCount = dynamicShortcuts.count { shortcutInfo ->
            shortcutInfo.activity == componentName
        } + shortcutManager.manifestShortcuts.count { shortcutInfo ->
            shortcutInfo.activity == componentName
        }

        if (shortcutCount >= shortcutManager.maxShortcutCountPerActivity) {
            return
        }

        //启动 LeakActivity 的Intent
        val intent = leakDisplayActivityIntent
        intent.action = "Dummy Action because Android is stupid"
        val shortcut = Builder(application, DYNAMIC_SHORTCUT_ID)
                .setLongLabel(longLabel)
                .setShortLabel(shortLabel)
                .setActivity(componentName)
                .setIcon(Icon.createWithResource(application, R.mipmap.leak_canary_icon))
                .setIntent(intent)
                .build()

        try {
            shortcutManager.addDynamicShortcuts(listOf(shortcut))
        } catch (ignored: Throwable) {
            SharkLog.d(ignored) {
                "Could not add dynamic shortcut. " +
                        "shortcutCount=$shortcutCount, " +
                        "maxShortcutCountPerActivity=${shortcutManager.maxShortcutCountPerActivity}"
            }
        }
    }

    //当有异常对象是会回调
    override fun onObjectRetained() {
        if (this::heapDumpTrigger.isInitialized) {
            heapDumpTrigger.onObjectRetained()
        }
    }

    fun onDumpHeapReceived(forceDump: Boolean) {
        if (this::heapDumpTrigger.isInitialized) {
            heapDumpTrigger.onDumpHeapReceived(forceDump)
        }
    }

    fun setEnabledBlocking(
            componentClassName: String,
            enabled: Boolean
    ) {
        val component = ComponentName(application, componentClassName)
        val newState =
                if (enabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED
        // Blocks on IPC.
        application.packageManager.setComponentEnabledSetting(component, newState, DONT_KILL_APP)
    }

    inline fun <reified T : Any> noOpDelegate(): T {
        val javaClass = T::class.java
        val noOpHandler = InvocationHandler { _, _, _ ->
            // no op
        }
        return Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(javaClass), noOpHandler
        ) as T
    }

    private const val LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump"
}
