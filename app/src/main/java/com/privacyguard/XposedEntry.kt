package com.privacyguard
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
class XposedEntry : XposedModule() {
 override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
  if (param.packageName=="android" || param.packageName.startsWith("com.android.providers.")) return
  PrivacyHooks.install(this,param)
 }
}
