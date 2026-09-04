package com.privacyguard

import android.app.Application
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

object PrivacyHooks {
    private const val POLICY_AUTHORITY = "com.privacyguard.policy"

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName == "com.privacyguard") return
        try {
            val method = ContentResolver::class.java.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java
            )

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val uri = chain.getArg(0) as? Uri
                    val policy = readPolicy()
                    if (!policy.enabled) return@intercept chain.proceed()

                    val rule = UriPolicy.ruleFor(uri)
                    if (!policy.rules.contains(rule)) {
                        return@intercept chain.proceed()
                    }

                    val projection = chain.getArg(1) as? Array<*>
                    val columns = projection?.mapNotNull { it as? String }?.toTypedArray() ?: emptyArray()
                    MatrixCursor(columns, 0)
                }

            module.log(Log.INFO, "PrivacyGuard", "Policy hook ready for ${param.packageName}")
        } catch (t: Throwable) {
            module.log(Log.ERROR, "PrivacyGuard", "Install failed for ${param.packageName}: ${t.message}", t)
        }
    }

    private data class Policy(val enabled: Boolean, val rules: Set<PrivacyRule>)

    private fun readPolicy(): Policy {
        return try {
            val app = currentApplication() ?: return Policy(false, emptySet())
            val result = app.contentResolver.call(
                Uri.parse("content://$POLICY_AUTHORITY"),
                "get_policy",
                null,
                null
            ) ?: return Policy(false, emptySet())
            val rules = PrivacyRule.values().filterTo(mutableSetOf()) { result.getBoolean(it.name, false) }
            Policy(result.getBoolean("enabled", false), rules)
        } catch (_: Throwable) {
            Policy(false, emptySet())
        }
    }

    private fun currentApplication(): Application? {
        return try {
            val cls = Class.forName("android.app.ActivityThread")
            cls.getDeclaredMethod("currentApplication").invoke(null) as? Application
        } catch (_: Throwable) {
            null
        }
    }
}
