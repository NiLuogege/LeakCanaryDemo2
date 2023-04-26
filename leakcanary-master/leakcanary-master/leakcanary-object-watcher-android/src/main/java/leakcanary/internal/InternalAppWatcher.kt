package leakcanary.internal

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import leakcanary.Clock
import leakcanary.AppWatcher
import leakcanary.ObjectWatcher
import leakcanary.OnObjectRetainedListener
import shark.SharkLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Note: this object must load fine in a JUnit environment
 */
internal object InternalAppWatcher {

    val isInstalled
        get() = ::application.isInitialized

    private val onAppWatcherInstalled: (Application) -> Unit

    val isDebuggableBuild by lazy {
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    //ContentProvider 中传过来的 application
    lateinit var application: Application

    private val clock = object : Clock {
        override fun uptimeMillis(): Long {
            return SystemClock.uptimeMillis()
        }
    }

    private val mainHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    init {
        val internalLeakCanary = try {
            //这里用反射的形式 初始化 一个InternalLeakCanary
            val leakCanaryListener = Class.forName("leakcanary.internal.InternalLeakCanary")
            leakCanaryListener.getDeclaredField("INSTANCE").get(null)
        } catch (ignored: Throwable) {
            NoLeakCanary
        }
        @kotlin.Suppress("UNCHECKED_CAST")
        onAppWatcherInstalled = internalLeakCanary as (Application) -> Unit
    }

    private val checkRetainedExecutor = Executor {
        mainHandler.postDelayed(it, AppWatcher.config.watchDurationMillis)
    }

    //对象观察器 相当于 1.x 版本中的 RefWatcher
    val objectWatcher = ObjectWatcher(
            clock = clock,
            checkRetainedExecutor = checkRetainedExecutor,
            isEnabled = { AppWatcher.config.enabled }
    )

    fun install(application: Application) {
        SharkLog.logger = DefaultCanaryLog()
        SharkLog.d { "Installing AppWatcher" }
        //判断是否是主线程
        checkMainThread()
        //判断是否已经初始化了
        if (this::application.isInitialized) {
            return
        }
        InternalAppWatcher.application = application

        //获取配置 ，这里为啥要将  AppWatcher.config 包装为一个方法返回值呢？
        val configProvider = { AppWatcher.config }
        //安装 ActivityDestroyWatcher
        ActivityDestroyWatcher.install(application, objectWatcher, configProvider)
        //安装 FragmentDestroyWatcher
        FragmentDestroyWatcher.install(application, objectWatcher, configProvider)
        // 调用 InternalLeakCanary 的 invoke 方法
        onAppWatcherInstalled(application)
    }

    inline fun <reified T : Any> noOpDelegate(): T {
        val javaClass = T::class.java
        val noOpHandler = InvocationHandler { _, _, _ ->
            // no op
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(javaClass), noOpHandler
        ) as T
    }

    private fun checkMainThread() {
        if (Looper.getMainLooper().thread !== Thread.currentThread()) {
            throw UnsupportedOperationException(
                    "Should be called from the main thread, not ${Thread.currentThread()}"
            )
        }
    }

    object NoLeakCanary : (Application) -> Unit, OnObjectRetainedListener {
        override fun invoke(application: Application) {
        }

        override fun onObjectRetained() {
        }
    }
}