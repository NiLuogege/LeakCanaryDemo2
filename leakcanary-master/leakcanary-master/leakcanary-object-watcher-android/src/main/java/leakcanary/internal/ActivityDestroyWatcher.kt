/*
 * Copyright (C) 2015 Square, Inc.
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
import leakcanary.AppWatcher.Config
import leakcanary.ObjectWatcher
import leakcanary.internal.InternalAppWatcher.noOpDelegate

/**
 * activity Destroy 以后会被添加到 objectWatcher 中
 */
internal class ActivityDestroyWatcher private constructor(
        private val objectWatcher: ObjectWatcher,
        private val configProvider: () -> Config
) {

    //这里会感知到 activity 的 destory 方法调用
    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks by noOpDelegate() {
        override fun onActivityDestroyed(activity: Activity) {
            //如果 允许 watchActivitie
            if (configProvider().watchActivities) {
                //观察 activity 并添加描述
                objectWatcher.watch(
                        activity, "${activity::class.java.name} received Activity#onDestroy() callback"
                )
            }
        }
    }

    companion object {
        fun install(
                application: Application,
                objectWatcher: ObjectWatcher,
                configProvider: () -> Config
        ) {
            //创建 ActivityDestroyWatcher
            val activityDestroyWatcher = ActivityDestroyWatcher(objectWatcher, configProvider)
            //添加 全局activity生命周期 监听器
            application.registerActivityLifecycleCallbacks(activityDestroyWatcher.lifecycleCallbacks)
        }
    }
}
