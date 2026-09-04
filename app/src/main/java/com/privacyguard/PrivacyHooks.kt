package com.privacyguard

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

object PrivacyHooks {
    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val method = ContentResolver::class.java.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java
            )

            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val uri = chain.getArg(0) as? Uri
                    val projection = chain.getArg(1) as? Array<*>
                    return if (UriPolicy.isSensitive(uri)) {
                        val columns = projection?.mapNotNull { it as? String }?.toTypedArray() ?: emptyArray()
                        MatrixCursor(columns, 0)
                    } else {
                        chain.proceed()
                    }
                }
            })

            module.log(Log.INFO, "PrivacyGuard", "Installed for ${param.packageName}")
        } catch (t: Throwable) {
            module.log(Log.ERROR, "PrivacyGuard", "Install failed: ${t.message}")
        }
    }
}
