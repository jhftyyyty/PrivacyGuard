package com.privacyguard

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

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

            module.hook(queryMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val uri = chain.getArg(0) as? Uri
                    val projection = chain.getArg(1) as? Array<*> as? Array<String>

                    if (UriPolicy.isSensitive(uri)) {
                        if (projection != null) {
                            MatrixCursor(projection, 0)
                        } else {
                            // No projection was requested. Returning an empty cursor still
                            // prevents the target app from receiving provider rows.
                            MatrixCursor(arrayOf<String>(), 0)
                        }
                    } else {
                        chain.proceed()
                    }
                }

            module.log(
                Log.INFO,
                "PrivacyGuard",
                "Installed for ${param.packageName}"
            )
        } catch (t: Throwable) {
            module.log(
                Log.ERROR,
                "PrivacyGuard",
                "Hook installation failed for ${param.packageName}",
                t
            )
        }
    }
}
