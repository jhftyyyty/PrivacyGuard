package com.privacyguard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS = "privacy_policies"
private const val UI_PREFS = "privacy_guard"
private const val NOTIFICATION_CHANNEL = "privacy_guard"

enum class ThemeChoice { AUTO, LIGHT, AMOLED }
enum class PrivacyRule(val title: String) {
    CONTACTS("جهات الاتصال"), CALL_LOGS("سجل المكالمات"), SMS("الرسائل SMS"),
    MMS("رسائل MMS"), MEDIA("الصور والفيديو والوسائط"), FILES("الملفات والمجلدات")
}

data class AppItem(
    val info: ApplicationInfo,
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val icon: Drawable?
)

@Composable
fun PrivacyTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.AUTO -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.AMOLED -> true
    }
    val scheme = if (dark) darkColorScheme(
        background = androidx.compose.ui.graphics.Color.Black,
        surface = androidx.compose.ui.graphics.Color.Black
    ) else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        setContent {
            val prefs = remember { getSharedPreferences(UI_PREFS, MODE_PRIVATE) }
            var theme by remember {
                mutableStateOf(runCatching { ThemeChoice.valueOf(prefs.getString("theme", "AUTO") ?: "AUTO") }.getOrDefault(ThemeChoice.AUTO))
            }
            PrivacyTheme(theme) {
                PrivacyGuardScreen(theme) {
                    theme = it
                    prefs.edit().putString("theme", it.name).apply()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL, "Privacy Guard", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun notifyProtection(packageName: String, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            return
        }
        val text = if (enabled) "تم تفعيل حماية $packageName. تأكد أن التطبيق موجود في LSPosed Scope." else "تم إيقاف حماية $packageName."
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(com.privacyguard.R.drawable.ic_privacy_guard)
            .setContentTitle("Privacy Guard")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(packageName.hashCode(), notification)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyGuardScreen(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.packageManager
    val prefs = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }
    var showSystemApps by remember { mutableStateOf(prefs.getBoolean("show_system_apps", false)) }
    var menu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var globalSettings by remember { mutableStateOf(false) }
    val enabledMap = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        // Keep the main list responsive: policy reads happen once, not during every row composition.
        val policyPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        withContext(Dispatchers.IO) {
            // getAll() is small and avoids a PackageManager/policy lookup on every scroll.
            policyPrefs.all.keys.filter { it.endsWith(".enabled") }.forEach { key ->
                val pkg = key.removeSuffix(".enabled")
                enabledMap[pkg] = policyPrefs.getBoolean(key, false)
            }
        }
    }

    val apps by produceState<List<AppItem>>(initialValue = emptyList(), key1 = pm) {
        value = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName }
                .map { info ->
                    AppItem(
                        info = info,
                        label = pm.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    if (selectedPackage != null) {
        BackHandler { selectedPackage = null }
        AppPolicyScreen(selectedPackage!!, pm, onBack = { selectedPackage = null })
        return
    }
    if (globalSettings) BackHandler { globalSettings = false }

    val query = search.trim().lowercase()
    val shown = apps.asSequence()
        .filter { showSystemApps || !it.isSystem }
        .filter { query.isEmpty() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
        .sortedWith(compareByDescending<AppItem> { enabledMap[it.packageName] == true }.thenBy { it.label.lowercase() })
        .toList()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (globalSettings) "الإعدادات" else "التطبيقات") },
            navigationIcon = {
                if (globalSettings) IconButton(onClick = { globalSettings = false }) { Icon(Icons.Default.ArrowBack, "رجوع") }
            },
            actions = {
                if (!globalSettings) {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "المزيد") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (showSystemApps) "إخفاء تطبيقات النظام" else "إظهار تطبيقات النظام") },
                            onClick = {
                                showSystemApps = !showSystemApps
                                prefs.edit().putBoolean("show_system_apps", showSystemApps).apply()
                                menu = false
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("الإعدادات") }, onClick = { globalSettings = true; menu = false })
                    }
                }
            }
        )
    }) { padding ->
        if (globalSettings) {
            GlobalSettings(theme, onTheme, Modifier.padding(padding))
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    singleLine = true, leadingIcon = { Icon(Icons.Default.Search, "بحث") },
                    placeholder = { Text("ابحث باسم التطبيق أو اسم الحزمة") }, shape = RoundedCornerShape(14.dp)
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.packageName }, contentType = { "app" }) { app ->
                        AppRow(app, enabledMap[app.packageName] == true,
                            onClick = { selectedPackage = app.packageName },
                            onEnabledChange = { enabled ->
                                enabledMap[app.packageName] = enabled
                                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("${app.packageName}.enabled", enabled).apply()
                                (context as? MainActivity)?.notifyProtection(app.packageName, enabled)
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppItem, enabled: Boolean, onClick: () -> Unit, onEnabledChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            AndroidView(factory = { ctx -> ImageViewCompat.create(ctx) }, update = { view -> view.setImageDrawable(app.icon) }, modifier = Modifier.size(48.dp))
        },
        headlineContent = { Text(app.label, maxLines = 1) },
        supportingContent = { Text(app.packageName, maxLines = 1) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.isSystem) AssistChip(onClick = {}, label = { Text("نظام") })
                Spacer(Modifier.width(8.dp))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
        }
    )
    HorizontalDivider()
}

private object ImageViewCompat {
    fun create(context: Context): android.widget.ImageView = android.widget.ImageView(context).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    }
}

@Composable
private fun GlobalSettings(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("المظهر", style = MaterialTheme.typography.titleLarge)
        listOf(ThemeChoice.AUTO to "تلقائي", ThemeChoice.LIGHT to "نهاري", ThemeChoice.AMOLED to "AMOLED").forEach { (value, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f)); RadioButton(theme == value, onClick = { onTheme(value) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPolicyScreen(packageName: String, pm: PackageManager, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val appInfo = remember(packageName) { runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() }
    val appName = remember(packageName) { appInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName }
    var protectionEnabled by remember(packageName) { mutableStateOf(prefs.getBoolean("$packageName.enabled", false)) }
    var rules by remember(packageName) { mutableStateOf(PrivacyRule.values().associateWith { prefs.getBoolean("$packageName.${it.name}", false) }) }
    val storedPaths = remember(packageName) { prefs.getStringSet("$packageName.custom_paths", emptySet())?.toList() ?: emptyList() }
    var paths by remember(packageName) { mutableStateOf(storedPaths) }
    var newPath by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("إعدادات الخصوصية") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                ListItem(headlineContent = { Text("تفعيل حماية هذا التطبيق") }, supportingContent = { Text(if (protectionEnabled) "الحماية مفعّلة" else "الحماية غير مفعّلة") }, trailingContent = {
                    Switch(checked = protectionEnabled, onCheckedChange = {
                        protectionEnabled = it
                        prefs.edit().putBoolean("$packageName.enabled", it).apply()
                        (context as? MainActivity)?.notifyProtection(packageName, it)
                    }
                })
                HorizontalDivider()
                Text("ما الذي تريد حجبه عن هذا التطبيق؟", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
            items(PrivacyRule.values().toList()) { rule ->
                val checked = rules[rule] == true
                ListItem(headlineContent = { Text(rule.title) }, supportingContent = { Text(if (checked) "محجوب" else "مسموح") }, trailingContent = {
                    Switch(checked) {
                        rules = rules.toMutableMap().apply { put(rule, it) }
                        prefs.edit().putBoolean("$packageName.${rule.name}", it).apply()
                    }
                })
            }
            item {
                Text("المجلدات المحجوبة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                paths.forEach { path ->
                    ListItem(headlineContent = { Text(path, maxLines = 2) }, trailingContent = {
                        TextButton(onClick = {
                            paths = paths.filterNot { it == path }
                            prefs.edit().putStringSet("$packageName.custom_paths", paths.toSet()).apply()
                        }) { Text("حذف") }
                    })
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newPath, { newPath = it }, Modifier.weight(1f), singleLine = true, label = { Text("مسار المجلد") }, placeholder = { Text("/storage/emulated/0/DCIM") })
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val clean = newPath.trim().trimEnd('/')
                        if (clean.isNotEmpty() && !paths.contains(clean)) {
                            paths = paths + clean
                            prefs.edit().putStringSet("$packageName.custom_paths", paths.toSet()).apply()
                            newPath = ""
                        }
                    }) { Text("إضافة") }
                }
                Text("يمكن إضافة أكثر من مجلد. فعّل «الملفات والمجلدات» حتى تطبق الحماية على المسارات المحددة.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
