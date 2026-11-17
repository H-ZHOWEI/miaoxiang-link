package com.miaoxiang.link

import android.app.Application
import com.updaterkit.UpdaterKit

/** 全局初始化：版本更新模块（每次打开 App 自动检查 GitHub Release）。 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdaterKit.init(
            UpdaterKit.Config(
                owner = "H-ZHOWEI",
                repo = "miaoxiang-link",
                appName = "MiaoXiangLink",
                assetSuffix = ".apk"
            )
        )
    }
}
