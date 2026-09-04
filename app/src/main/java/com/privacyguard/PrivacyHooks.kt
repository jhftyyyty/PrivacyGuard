package com.privacyguard

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedInterface

object PrivacyHooks {
    fun install(
        module: XposedModule,
        param: XposedModuleInterface.PackageLoadedParam
    ) {
        try {
            val queryMethod = ContentResolver::class.java.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java
            )

            module.hook(queryMethod).intercept(object :
                XposedInterface.Hooker<Any?> {
                override fun intercept(
                    chain: XposedInterface.Chain<Any?>
                ): Any? {
                    val uri = chain.getArg(0) as? Uri
                    val projection = chain.getArg(1) as? Array<String>

                    return if (UriPolicy.isSensitive(uri) && projection != null) {
                        MatrixCursor(projection, 0)
                    } else {
                        chain.proceed()
                    }
                }
            })

            module.log("PrivacyGuard: installed for ${param.packageName}")
        } catch (t: Throwable) {
            module.log("PrivacyGuard: ${t.stackTraceToString()}")
        }
    }
}
