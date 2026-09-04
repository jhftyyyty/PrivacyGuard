package com.privacyguard

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XposedEntry : XposedModule() {
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName == "android" ||
            param.packageName == "com.android.providers.contacts" ||
            param.packageName == "com.android.providers.telephony" ||
            param.packageName == "com.android.providers.media"
        ) return

        PrivacyHooks.install(this, param)
    }
}
