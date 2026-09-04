package com.privacyguard

import android.app.Application
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.io.FileNotFoundException

object PrivacyHooks {
    private const val POLICY_AUTHORITY = "com.privacyguard.policy"

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName == "com.privacyguard" || param.packageName == "android" || param.packageName.startsWith("com.android.providers.")) return
        try {
            hookQuery(module, param)
            hookOpenInputStream(module)
            hookOpenFileDescriptor(module)
            hookFileInputStream(module)
            hookFileOutputStream(module)
            hookFileListing(module)
            module.log(Log.INFO, "PrivacyGuard", "Privacy hooks ready for ${param.packageName}")
        } catch (t: Throwable) {
            module.log(Log.ERROR, "PrivacyGuard", "Install failed for ${param.packageName}: ${t.message}", t)
        }
    }

    private fun hookQuery(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val method = ContentResolver::class.java.getDeclaredMethod(
            "query", Uri::class.java, Array<String>::class.java, String::class.java,
            Array<String>::class.java, String::class.java
        )
        module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val policy = readPolicy()
            val uri = chain.getArg(0) as? Uri
            if (!policy.blocks(ruleForUri(uri))) return@intercept chain.proceed()
            val projection = chain.getArg(1) as? Array<*>
            val columns = projection?.mapNotNull { it as? String }?.toTypedArray() ?: emptyArray()
            MatrixCursor(columns, 0)
        }
    }

    private fun hookOpenInputStream(module: XposedModule) {
        val method = ContentResolver::class.java.getDeclaredMethod("openInputStream", Uri::class.java)
        module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val policy = readPolicy()
            val uri = chain.getArg(0) as? Uri
            if (policy.blocks(ruleForUri(uri)) || (policy.enabled && policy.customPaths.isNotEmpty() && ruleForUri(uri) == PrivacyRule.FILES)) {
                throw FileNotFoundException("Privacy Guard blocked content")
            }
            chain.proceed()
        }
    }

    private fun hookOpenFileDescriptor(module: XposedModule) {
        val method = ContentResolver::class.java.getDeclaredMethod("openFileDescriptor", Uri::class.java, String::class.java, android.os.CancellationSignal::class.java)
        module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val policy = readPolicy()
            val uri = chain.getArg(0) as? Uri
            if (policy.blocks(ruleForUri(uri)) || (policy.enabled && policy.blocks(PrivacyRule.FILES))) {
                throw FileNotFoundException("Privacy Guard blocked file descriptor")
            }
            chain.proceed()
        }
    }

    private fun hookFileInputStream(module: XposedModule) {
        val cls = java.io.FileInputStream::class.java
        listOf(cls.getDeclaredConstructor(File::class.java), cls.getDeclaredConstructor(String::class.java)).forEach { constructor ->
            module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val path = when (val arg = chain.getArg(0)) {
                    is File -> arg.absolutePath
                    is String -> File(arg).absolutePath
                    else -> ""
                }
                if (readPolicy().blocksPath(path)) throw FileNotFoundException("Privacy Guard blocked path")
                chain.proceed()
            }
        }
    }

    private fun hookFileOutputStream(module: XposedModule) {
        val cls = java.io.FileOutputStream::class.java
        listOf(cls.getDeclaredConstructor(File::class.java), cls.getDeclaredConstructor(String::class.java)).forEach { constructor ->
            module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val path = when (val arg = chain.getArg(0)) {
                    is File -> arg.absolutePath
                    is String -> File(arg).absolutePath
                    else -> ""
                }
                if (readPolicy().blocksPath(path)) throw FileNotFoundException("Privacy Guard blocked path")
                chain.proceed()
            }
        }
    }

    private fun hookFileListing(module: XposedModule) {
        val listFiles = File::class.java.getDeclaredMethod("listFiles")
        module.hook(listFiles).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val file = chain.getThisObject() as? File
            val policy = readPolicy()
            if (file != null && policy.blocksPath(file.absolutePath)) return@intercept emptyArray<File>()
            val result = chain.proceed() as? Array<File>
            if (result != null && policy.enabled && policy.customPaths.isNotEmpty()) {
                result.filterNot { policy.blocksPath(it.absolutePath) }.toTypedArray()
            } else result
        }

        val list = File::class.java.getDeclaredMethod("list")
        module.hook(list).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val file = chain.getThisObject() as? File
            val policy = readPolicy()
            if (file != null && policy.blocksPath(file.absolutePath)) return@intercept emptyArray<String>()
            val result = chain.proceed() as? Array<String>
            if (result != null && policy.enabled && policy.customPaths.isNotEmpty()) {
                result.filterNot { child -> policy.blocksPath(File(file, child).absolutePath) }.toTypedArray()
            } else result
        }
    }

    private data class Policy(val enabled: Boolean, val rules: Set<PrivacyRule>, val customPaths: Set<String>) {
        fun blocks(rule: PrivacyRule?): Boolean = enabled && rule != null && rules.contains(rule)
        fun blocksPath(path: String): Boolean = enabled && rules.contains(PrivacyRule.FILES) && customPaths.any { root ->
            val normalized = normalize(path)
            val r = normalize(root)
            normalized == r || normalized.startsWith(if (r.endsWith('/')) r else "$r/")
        }
    }

    private fun readPolicy(): Policy {
        return try {
            val app = currentApplication() ?: return Policy(false, emptySet(), emptySet())
            val result = app.contentResolver.call(Uri.parse("content://$POLICY_AUTHORITY"), "get_policy", null, null)
                ?: return Policy(false, emptySet(), emptySet())
            val rules = PrivacyRule.values().filterTo(mutableSetOf()) { result.getBoolean(it.name, false) }
            val paths = result.getStringArrayList("custom_paths")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            Policy(result.getBoolean("enabled", false), rules, paths)
        } catch (_: Throwable) {
            Policy(false, emptySet(), emptySet())
        }
    }

    private fun normalize(path: String): String = path.trim().removeSuffix("/").replace("//", "/")

    private fun currentApplication(): Application? = try {
        val cls = Class.forName("android.app.ActivityThread")
        cls.getDeclaredMethod("currentApplication").invoke(null) as? Application
    } catch (_: Throwable) { null }
}

private fun ruleForUri(uri: Uri?): PrivacyRule? = when (uri?.authority?.lowercase()) {
    "com.android.contacts", "contacts" -> PrivacyRule.CONTACTS
    "call_log", "com.android.calllog" -> PrivacyRule.CALL_LOGS
    "sms", "mms", "mms-sms", "telephony" -> if (uri.authority?.lowercase() == "mms") PrivacyRule.MMS else PrivacyRule.SMS
    "media", "com.android.providers.media.documents" -> PrivacyRule.MEDIA
    else -> null
}
