package com.privacyguard

import android.app.Application
import android.content.ContentResolver
import android.content.ContentProviderClient
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import android.os.ParcelFileDescriptor
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
            hookContentProviderClient(module)
            hookContentOpens(module)
            hookMediaThumbnails(module)
            hookFileInputStream(module)
            hookRandomAccessFile(module)
            hookParcelFileDescriptor(module)
            hookMediaPathReaders(module)
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
     * Some gallery implementations acquire a ContentProviderClient and then
     * talk to the provider directly, bypassing ContentResolver.query/open*.
     * Hook those entry points as well.
     */
    private fun hookContentProviderClient(module: XposedModule) {
        ContentProviderClient::class.java.declaredMethods
            .filter { method ->
                method.name in setOf("query", "openFile", "openAssetFile", "openTypedAssetFile") &&
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

                        if (method.name == "query") {
                            val projection = chain.getArg(1) as? Array<*>
                            val columns = projection?.mapNotNull { it as? String }?.toTypedArray() ?: emptyArray()
                            MatrixCursor(columns, 0)
                        } else {
                            throw FileNotFoundException("Privacy Guard blocked content provider access")
                        }
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

    /**
     * Gallery apps frequently request thumbnails through ContentResolver.loadThumbnail
     * instead of opening the original media URI. Blocking only openInputStream/openFileDescriptor
     * therefore leaves cached/indexed thumbnails visible.
     */
    private fun hookMediaThumbnails(module: XposedModule) {
        ContentResolver::class.java.declaredMethods
            .filter { it.name == "loadThumbnail" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Uri::class.java }
            .forEach { method ->
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val policy = readPolicy()
                        val uri = chain.getArg(0) as? Uri
                        if (policy.blocks(PrivacyRule.MEDIA) && ruleForUri(uri) == PrivacyRule.MEDIA) {
                            throw FileNotFoundException("Privacy Guard blocked media thumbnail")
                        }
                        chain.proceed()
                    }
            }
    }

    /** RandomAccessFile is another common fallback when a gallery resolves a DATA path. */
    private fun hookRandomAccessFile(module: XposedModule) {
        java.io.RandomAccessFile::class.java.declaredConstructors
            .filter { c ->
                c.parameterTypes.size == 2 &&
                    (c.parameterTypes[0] == File::class.java || c.parameterTypes[0] == String::class.java)
            }
            .forEach { constructor ->
                module.hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val path = when (val arg = chain.getArg(0)) {
                            is File -> arg.absolutePath
                            is String -> File(arg).absolutePath
                            else -> ""
                        }
                        val policy = readPolicy()
                        if (policy.blocksPath(path) || policy.blocksMediaPath(path)) {
                            throw FileNotFoundException("Privacy Guard blocked path")
                        }
                        chain.proceed()
                    }
            }
    }

    /** Covers callers that bypass ContentResolver and open a resolved filesystem path directly. */
    private fun hookParcelFileDescriptor(module: XposedModule) {
        ParcelFileDescriptor::class.java.declaredMethods
            .filter { method ->
                java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.name == "open" &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == File::class.java
            }
            .forEach { method ->
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val file = chain.getArg(0) as? File
                        val path = file?.absolutePath.orEmpty()
                        val policy = readPolicy()
                        if (policy.blocksPath(path) || policy.blocksMediaPath(path)) {
                            throw FileNotFoundException("Privacy Guard blocked path")
                        }
                        chain.proceed()
                    }
            }
    }

    /**
     * Some gallery implementations bypass ContentResolver entirely after obtaining
     * a filesystem path. Cover common Android media readers used for previews/thumbnails.
     */
    private fun hookMediaPathReaders(module: XposedModule) {
        // BitmapFactory.decodeFile(String, ...)
        android.graphics.BitmapFactory::class.java.declaredMethods
            .filter { it.name == "decodeFile" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java }
            .forEach { method ->
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val path = chain.getArg(0) as? String ?: ""
                        if (readPolicy().blocksMediaPath(path)) return@intercept null
                        chain.proceed()
                    }
            }

        // MediaMetadataRetriever is commonly used to inspect video/image metadata before display.
        android.media.MediaMetadataRetriever::class.java.declaredMethods
            .filter { it.name == "setDataSource" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java }
            .forEach { method ->
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val path = chain.getArg(0) as? String ?: ""
                        if (readPolicy().blocksMediaPath(path)) {
                            throw FileNotFoundException("Privacy Guard blocked media path")
                        }
                        chain.proceed()
                    }
            }

        // Android 9+ ImageDecoder can create an image source directly from a URI or file.
        runCatching { Class.forName("android.graphics.ImageDecoder") }.getOrNull()?.let { decoderClass ->
            decoderClass.declaredMethods
                .filter { it.name == "createSource" && it.parameterTypes.isNotEmpty() }
                .filter { method ->
                    method.parameterTypes.any { it == Uri::class.java || it == File::class.java }
                }
                .forEach { method ->
                    module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val policy = readPolicy()
                            val blocked = chain.getArg(0)?.let { first ->
                                when (first) {
                                    is Uri -> policy.blocks(PrivacyRule.MEDIA) && ruleForUri(first) == PrivacyRule.MEDIA
                                    is File -> policy.blocksMediaPath(first.absolutePath)
                                    else -> false
                                }
                            } ?: false
                            if (blocked) throw FileNotFoundException("Privacy Guard blocked image source")
                            chain.proceed()
                        }
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
                val policy = readPolicy()
                if (policy.blocksPath(path) || policy.blocksMediaPath(path)) throw FileNotFoundException("Privacy Guard blocked path")
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
                val policy = readPolicy()
                if (policy.blocksPath(path) || policy.blocksMediaPath(path)) throw FileNotFoundException("Privacy Guard blocked path")
                chain.proceed()
            }
        }
    }

    private fun hookFileListing(module: XposedModule) {
        val listFiles = File::class.java.getDeclaredMethod("listFiles")
        module.hook(listFiles).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val file = chain.getThisObject() as? File
            val policy = readPolicy()
            if (file != null && (policy.blocksPath(file.absolutePath) || policy.blocksMediaPath(file.absolutePath))) return@intercept emptyArray<File>()
            val result = chain.proceed() as? Array<File>
            if (result != null && policy.enabled && policy.customPaths.isNotEmpty()) {
                result.filterNot { policy.blocksPath(it.absolutePath) || policy.blocksMediaPath(it.absolutePath) }.toTypedArray()
            } else result
        }

        val list = File::class.java.getDeclaredMethod("list")
        module.hook(list).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val file = chain.getThisObject() as? File
            val policy = readPolicy()
            if (file != null && (policy.blocksPath(file.absolutePath) || policy.blocksMediaPath(file.absolutePath))) return@intercept emptyArray<String>()
            val result = chain.proceed() as? Array<String>
            if (result != null && policy.enabled && policy.customPaths.isNotEmpty()) {
                result.filterNot { child -> file != null && policy.blocksPath(File(file, child).absolutePath) || policy.blocksMediaPath(File(file, child).absolutePath) }.toTypedArray()
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

        /**
         * MediaStore is the preferred API, but some gallery implementations still walk
         * well-known shared-storage media directories directly. Apply the MEDIA rule to
         * those directories as a second line of defense.
         */
        fun blocksMediaPath(path: String): Boolean {
            if (!enabled || !rules.contains(PrivacyRule.MEDIA)) return false
            val p = normalize(path)
            val roots = listOf(
                "/storage/emulated/0/DCIM",
                "/storage/emulated/0/Pictures",
                "/storage/emulated/0/Movies",
                "/storage/emulated/0/Recordings"
            )
            return roots.any { root -> p == root || p.startsWith("$root/") }
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
    "com.google.android.apps.photos.content",
    "com.google.android.apps.photos.api",
    "com.miui.gallery.provider",
    "com.sec.android.gallery3d.provider",
    "com.oneplus.gallery.provider",
    "com.coloros.gallery3d",
    "com.huawei.photos" -> PrivacyRule.MEDIA
    else -> null
}

