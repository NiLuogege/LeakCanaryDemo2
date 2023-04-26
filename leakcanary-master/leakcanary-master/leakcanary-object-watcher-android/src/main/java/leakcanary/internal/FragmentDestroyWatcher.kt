/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary.internal

import android.app.Activity
import android.app.Application
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.Bundle
import leakcanary.AppWatcher
import leakcanary.ObjectWatcher
import leakcanary.internal.InternalAppWatcher.noOpDelegate

/**
 * Internal class used to watch for fragments leaks.
 */
internal object FragmentDestroyWatcher {

    private const val ANDROIDX_FRAGMENT_CLASS_NAME = "androidx.fragment.app.Fragment"
    private const val ANDROIDX_FRAGMENT_DESTROY_WATCHER_CLASS_NAME =
            "leakcanary.internal.AndroidXFragmentDestroyWatcher"

    private const val ANDROID_SUPPORT_FRAGMENT_CLASS_NAME = "android.support.v4.app.Fragment"
    private const val ANDROID_SUPPORT_FRAGMENT_DESTROY_WATCHER_CLASS_NAME =
            "leakcanary.internal.AndroidSupportFragmentDestroyWatcher"

    fun install(
            application: Application,
            objectWatcher: ObjectWatcher,
            configProvider: () -> AppWatcher.Config
    ) {
        val fragmentDestroyWatchers = mutableListOf<(Activity) -> Unit>()

        //大于 android o
        if (SDK_INT >= O) {
            fragmentDestroyWatchers.add(
                    AndroidOFragmentDestroyWatcher(objectWatcher, configProvider)
            )
        }

        //针对 androidx.fragment.app.Fragment
        getWatcherIfAvailable(
                ANDROIDX_FRAGMENT_CLASS_NAME,
                ANDROIDX_FRAGMENT_DESTROY_WATCHER_CLASS_NAME,
                objectWatcher,
                configProvider
        )?.let {
            fragmentDestroyWatchers.add(it)
        }

        //针对 support.v4.app.Fragment
        getWatcherIfAvailable(
                ANDROID_SUPPORT_FRAGMENT_CLASS_NAME,
                ANDROID_SUPPORT_FRAGMENT_DESTROY_WATCHER_CLASS_NAME,
                objectWatcher,
                configProvider
        )?.let {
            fragmentDestroyWatchers.add(it)
        }

        //说明没有一种fragment 可以被监控
        if (fragmentDestroyWatchers.size == 0) {
            return
        }

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks by noOpDelegate() {
          override fun onActivityCreated(
                  activity: Activity,
                  savedInstanceState: Bundle?
          ) {
            //当activity create的时候 添加 fragment生命周期监听，然后在fragment销毁的时候进行 watch
            for (watcher in fragmentDestroyWatchers) {
              //这里是遍历 所有的 fragmentDestroyWatcher 然后会调用他们里面的 invoke 方法方法进行声明周期监听的注册
              watcher(activity)
            }
          }
        })
    }

    //返回 leakcanary.internal.AndroidXFragmentDestroyWatcher 对象
    //这是针对 androidx fragment的
    private fun getWatcherIfAvailable(
            fragmentClassName: String, //androidx.fragment.app.Fragment
            watcherClassName: String,//leakcanary.internal.AndroidXFragmentDestroyWatcher
            objectWatcher: ObjectWatcher,
            configProvider: () -> AppWatcher.Config
    ): ((Activity) -> Unit)? {

        return if (classAvailable(fragmentClassName) &&
                classAvailable(watcherClassName)
        ) {
            val watcherConstructor = Class.forName(watcherClassName)
                    .getDeclaredConstructor(ObjectWatcher::class.java, Function0::class.java)
            @Suppress("UNCHECKED_CAST")
            watcherConstructor.newInstance(objectWatcher, configProvider) as (Activity) -> Unit

        } else {
            null
        }
    }

    private fun classAvailable(className: String): Boolean {
        return try {
            Class.forName(className)
            true
        } catch (e: Throwable) {
            // e is typically expected to be a ClassNotFoundException
            // Unfortunately, prior to version 25.0.2 of the support library the
            // FragmentManager.FragmentLifecycleCallbacks class was a non static inner class.
            // Our AndroidSupportFragmentDestroyWatcher class is compiled against the static version of
            // the FragmentManager.FragmentLifecycleCallbacks class, leading to the
            // AndroidSupportFragmentDestroyWatcher class being rejected and a NoClassDefFoundError being
            // thrown here. So we're just covering our butts here and catching everything, and assuming
            // any throwable means "can't use this". See https://github.com/square/leakcanary/issues/1662
            false
        }
    }
}
