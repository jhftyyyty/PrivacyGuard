package com.privacyguard

import android.app.Application
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
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
            hookQueries(module)
            hookContentOpens(module)
            hookFileInputStream(module)
            hookFileOutputStream(module)
            hookFileListing(module)
            module.log(Log.INFO, "PrivacyGuard", "Privacy hooks ready for ${param.packageName}")
        } catch (t: Throwable) {
            module.log(Log.ERROR, "PrivacyGuard", "Install failed for ${param.packageName}: ${t.message}", t)
        }
    }

    /**
     * Android has multiple ContentResolver.query overloads. Modern gallery/photo
     * apps commonly use the Bundle/CancellationSignal overload, so both forms
     * must be covered.
     */
    private fun hookQueries(module: XposedModule) {
        ContentResolver::class.java.declaredMethods
            .filter { method ->
                method.name == "query" &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == Uri::class.java
            }
            .forEach { method ->
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val policy = readPolicy()
                        val uri = chain.getArg(0) as? Uri
                        val rule = ruleForUri(uri)
                        if (!policy.blocks(rule)) return@intercept chain.proceed()

                        // query(...): return an empty cursor with the caller's
                        // requested projection so normal consumers do not crash.
                        val projection = chain.getArg(1) as? Array<*>
                        val columns = projection?.mapNotNull { it as? String }?.toTypedArray() ?: emptyArray()
                        MatrixCursor(columns, 0)
                    }
            }
    }

    /**
     * Cover all ContentResolver read-open APIs used by MediaStore, SAF and
     * Google Photos/Gallery implementations. Blocking the URI before it reaches
     * the provider prevents the media bytes from being exposed.
     */
    private fun hookContentOpens(module: XposedModule) {
        val names = setOf(
            "openInputStream",
            "openFileDescriptor",
            "openAssetFileDescriptor",
            "openTypedAssetFileDescriptor",
            "openFile"
        )

        ContentResolver::class.java.declaredMethods
            .filter { method ->
                method.name in names &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == Uri::class.java
            }
            .forEach { method ->
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val policy = readPolicy()
                        val uri = chain.getArg(0) as? Uri
                        val rule = ruleForUri(uri)
                        val blocked = policy.blocks(rule) ||
                            (policy.enabled && policy.rules.contains(PrivacyRule.FILES) && policy.uriLooksLikeCustomFile(uri))
                        if (blocked) {
                            throw FileNotFoundException("Privacy Guard blocked content")
                        }
                        chain.proceed()
                    }
            }
    }

    private fun hookFileInputStream(module: XposedModule) {
        listOf(
            java.io.FileInputStream::class.java.getDeclaredConstructor(File::class.java),
            java.io.FileInputStream::class.java.getDeclaredConstructor(String::class.java)
        ).forEach { constructor ->
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
        listOf(
            java.io.FileOutputStream::class.java.getDeclaredConstructor(File::class.java),
            java.io.FileOutputStream::class.java.getDeclaredConstructor(String::class.java)
        ).forEach { constructor ->
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
                result.filterNot { child -> file != null && policy.blocksPath(File(file, child).absolutePath) }.toTypedArray()
            } else result
        }
    }

    private data class Policy(
        val enabled: Boolean,
        val rules: Set<PrivacyRule>,
        val customPaths: Set<String>
    ) {
        fun blocks(rule: PrivacyRule?): Boolean = enabled && rule != null && rules.contains(rule)

        fun blocksPath(path: String): Boolean =
            enabled && rules.contains(PrivacyRule.FILES) && customPaths.any { root ->
                val normalized = normalize(path)
                val r = normalize(root)
                normalized == r || normalized.startsWith("$r/")
            }

        /** SAF document URIs can identify a custom primary-storage path. */
        fun uriLooksLikeCustomFile(uri: Uri?): Boolean {
            if (!enabled || !rules.contains(PrivacyRule.FILES) || uri == null || customPaths.isEmpty()) return false
            val text = uri.toString()
            return customPaths.any { root ->
                val name = root.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@any false
                text.contains(name, ignoreCase = true)
            }
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
    "sms" -> PrivacyRule.SMS
    "mms", "mms-sms" -> PrivacyRule.MMS
    "telephony" -> PrivacyRule.SMS
    "media",
    "com.android.providers.media.documents",
    "com.google.android.apps.photos.contentprovider",
    "com.miui.gallery.provider" -> PrivacyRule.MEDIA
    else -> null
}

