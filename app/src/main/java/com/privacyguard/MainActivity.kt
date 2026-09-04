package com.privacyguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

enum class ThemeChoice { AUTO, LIGHT, AMOLED }
enum class AppFilter { ALL, USER, SYSTEM }

enum class PrivacyRule(val title: String) {
    CONTACTS("جهات الاتصال"),
    CALL_LOGS("سجل المكالمات"),
    SMS("الرسائل SMS"),
    MMS("رسائل MMS"),
    MEDIA("الصور والفيديو والوسائط"),
    FILES("الملفات والمجلدات")
}

@Composable
fun PrivacyTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.AUTO -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.AMOLED -> true
    }
    val scheme = if (dark) darkColorScheme(
        background = if (choice == ThemeChoice.AMOLED) Color.Black else Color(0xFF121212),
        surface = if (choice == ThemeChoice.AMOLED) Color.Black else Color(0xFF121212)
    ) else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = remember { getSharedPreferences("privacy_guard", MODE_PRIVATE) }
            var theme by remember {
                mutableStateOf(runCatching {
                    ThemeChoice.valueOf(prefs.getString("theme", "AUTO") ?: "AUTO")
                }.getOrDefault(ThemeChoice.AUTO))
            }
            PrivacyTheme(theme) {
                PrivacyGuardScreen(
                    theme = theme,
                    onTheme = {
                        theme = it
                        prefs.edit().putString("theme", it.name).apply()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyGuardScreen(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.packageManager
    val apps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != context.packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
    }
    val prefs = remember { context.getSharedPreferences("privacy_guard", Context.MODE_PRIVATE) }
    var filter by remember {
        mutableStateOf(runCatching {
            AppFilter.valueOf(prefs.getString("filter", "ALL") ?: "ALL")
        }.getOrDefault(AppFilter.ALL))
    }
    var menu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var globalSettings by remember { mutableStateOf(false) }

    if (selectedPackage != null) {
        BackHandler { selectedPackage = null }
        AppPolicyScreen(
            packageName = selectedPackage!!,
            pm = pm,
            onBack = { selectedPackage = null }
        )
        return
    }

    if (globalSettings) {
        BackHandler { globalSettings = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (globalSettings) "الإعدادات" else "التطبيقات") },
                navigationIcon = {
                    if (globalSettings) {
                        IconButton(onClick = { globalSettings = false }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                        }
                    }
                },
                actions = {
                    if (!globalSettings) {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "المزيد")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("إظهار كل التطبيقات") },
                                onClick = {
                                    filter = AppFilter.ALL
                                    prefs.edit().putString("filter", "ALL").apply()
                                    menu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("إظهار التطبيقات العادية فقط") },
                                onClick = {
                                    filter = AppFilter.USER
                                    prefs.edit().putString("filter", "USER").apply()
                                    menu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("إظهار تطبيقات النظام فقط") },
                                onClick = {
                                    filter = AppFilter.SYSTEM
                                    prefs.edit().putString("filter", "SYSTEM").apply()
                                    menu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("إعدادات التطبيق") },
                                onClick = { globalSettings = true; menu = false }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (globalSettings) {
            GlobalSettings(theme, onTheme, Modifier.padding(padding))
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "بحث") },
                    placeholder = { Text("ابحث باسم التطبيق أو اسم الحزمة") },
                    shape = RoundedCornerShape(14.dp)
                )

                val query = search.trim().lowercase()
                val shown = apps.filter { app ->
                    val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    val isUpdatedSystem = app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                    val filterMatches = when (filter) {
                        AppFilter.ALL -> true
                        AppFilter.USER -> !isSystem || isUpdatedSystem
                        AppFilter.SYSTEM -> isSystem && !isUpdatedSystem
                    }
                    val name = pm.getApplicationLabel(app).toString()
                    filterMatches && (query.isEmpty() || name.lowercase().contains(query) || app.packageName.lowercase().contains(query))
                }

                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("لا توجد تطبيقات مطابقة للبحث")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.packageName }) { app ->
                            AppRow(app, pm) { selectedPackage = app.packageName }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: ApplicationInfo, pm: PackageManager, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                },
                update = { view ->
                    runCatching { view.setImageDrawable(pm.getApplicationIcon(app)) }
                        .onFailure { view.setImageDrawable(null) }
                },
                modifier = Modifier.size(48.dp)
            )
        },
        headlineContent = { Text(pm.getApplicationLabel(app).toString()) },
        supportingContent = { Text(app.packageName) },
        trailingContent = {
            val system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updated = app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (system && !updated) {
                AssistChip(onClick = {}, label = { Text("نظام") })
            }
        }
    )
    HorizontalDivider()
}

@Composable
private fun GlobalSettings(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("المظهر", style = MaterialTheme.typography.titleLarge)
        Text("الوضع الافتراضي: تلقائي")
        listOf(
            ThemeChoice.AUTO to "تلقائي",
            ThemeChoice.LIGHT to "نهاري",
            ThemeChoice.AMOLED to "AMOLED"
        ).forEach { (value, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                RadioButton(selected = theme == value, onClick = { onTheme(value) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPolicyScreen(packageName: String, pm: PackageManager, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("privacy_policies", Context.MODE_PRIVATE) }
    val appInfo = remember(packageName) { runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() }
    val appName = remember(packageName) { appInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName }
    var protectionEnabled by remember(packageName) {
        mutableStateOf(prefs.getBoolean("$packageName.enabled", false))
    }
    var rules by remember(packageName) {
        mutableStateOf(PrivacyRule.values().associateWith { rule -> prefs.getBoolean("$packageName.${rule.name}", false) })
    }
    var customPath by remember(packageName) { mutableStateOf(prefs.getString("$packageName.custom_path", "") ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("إعدادات الخصوصية") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AndroidView(
                    factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE } },
                    update = { view -> runCatching { if (appInfo != null) view.setImageDrawable(pm.getApplicationIcon(appInfo)) }.onFailure { view.setImageDrawable(null) } },
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(appName, style = MaterialTheme.typography.titleLarge)
                    Text(packageName, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    ListItem(
                        headlineContent = { Text("تفعيل حماية هذا التطبيق") },
                        supportingContent = { Text(if (protectionEnabled) "الحماية مفعّلة" else "الحماية غير مفعّلة") },
                        trailingContent = {
                            Switch(
                                checked = protectionEnabled,
                                onCheckedChange = { checked ->
                                    protectionEnabled = checked
                                    prefs.edit().putBoolean("$packageName.enabled", checked).apply()
                                }
                            )
                        }
                    )
                    HorizontalDivider()
                    Text("ما الذي تريد حجبه عن هذا التطبيق؟", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                }
                items(PrivacyRule.values().toList()) { rule ->
                    val enabled = rules[rule] == true
                    ListItem(
                        headlineContent = { Text(rule.title) },
                        supportingContent = { Text(if (enabled) "محجوب" else "مسموح") },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    rules = rules.toMutableMap().apply { put(rule, checked) }
                                    prefs.edit().putBoolean("$packageName.${rule.name}", checked).apply()
                                }
                            )
                        }
                    )
                }
                item {
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = {
                            customPath = it
                            prefs.edit().putString("$packageName.custom_path", it).apply()
                        },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        singleLine = true,
                        label = { Text("مجلد أو مسار مخصص") },
                        placeholder = { Text("مثال: /storage/emulated/0/DCIM") }
                    )
                    Text(
                        "يمكنك تحديد مسار إضافي تريد حمايته لهذا التطبيق.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
