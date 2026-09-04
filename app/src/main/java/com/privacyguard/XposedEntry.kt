package com.privacyguard

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XposedEntry : XposedModule() {
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!param.isFirstPackage) return
        PrivacyHooks.install(this, param)
    }
}
