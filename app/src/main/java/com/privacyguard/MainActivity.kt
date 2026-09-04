package com.privacyguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val PREFS = "privacy_policies"
private const val UI_PREFS = "privacy_guard"
private const val PROFILE_PREFS = "privacy_profiles"

private enum class Screen { APPS, SETTINGS, PROFILES, PROFILE_EDIT, PROFILE_APPS, POLICY }

enum class ThemeChoice { AUTO, LIGHT, AMOLED }
enum class PrivacyRule(val title: String) {
    CONTACTS("جهات الاتصال"), CALL_LOGS("سجل المكالمات"), SMS("الرسائل SMS"),
    MMS("رسائل MMS"), MEDIA("الصور والفيديو والوسائط"), FILES("الملفات والمجلدات")
}

data class AppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val icon: Bitmap?
)

data class PrivacyProfile(
    val id: String,
    val name: String,
    val rules: Set<PrivacyRule>,
    val paths: List<String>,
    val assignedApps: Set<String> = emptySet()
)

private object ProfileStore {
    fun ids(prefs: android.content.SharedPreferences): List<String> =
        prefs.getStringSet("ids", emptySet())?.toList()?.sortedBy { prefs.getString("$it.name", "")?.lowercase() ?: "" } ?: emptyList()

    fun load(prefs: android.content.SharedPreferences, id: String): PrivacyProfile {
        val rules = PrivacyRule.values().filterTo(mutableSetOf()) { prefs.getBoolean("$id.${it.name}", false) }
        val paths = prefs.getStringSet("$id.paths", emptySet())?.toList() ?: emptyList()
        val assignedApps = prefs.getStringSet("$id.apps", emptySet()) ?: emptySet()
        return PrivacyProfile(id, prefs.getString("$id.name", id) ?: id, rules, paths, assignedApps)
    }

    fun save(prefs: android.content.SharedPreferences, profile: PrivacyProfile) {
        val e = prefs.edit()
            .putStringSet("ids", (prefs.getStringSet("ids", emptySet()) ?: emptySet()) + profile.id)
            .putString("${profile.id}.name", profile.name)
            .putStringSet("${profile.id}.paths", profile.paths.toSet())
            .putStringSet("${profile.id}.apps", profile.assignedApps)
        PrivacyRule.values().forEach { e.putBoolean("${profile.id}.${it.name}", profile.rules.contains(it)) }
        e.apply()
    }

    fun delete(prefs: android.content.SharedPreferences, id: String) {
        val e = prefs.edit().putStringSet("ids", (prefs.getStringSet("ids", emptySet()) ?: emptySet()) - id)
        e.remove("$id.name").remove("$id.paths").remove("$id.apps")
        PrivacyRule.values().forEach { e.remove("$id.${it.name}") }
        e.apply()
    }
}

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
        setContent {
            val prefs = remember { getSharedPreferences(UI_PREFS, MODE_PRIVATE) }
            var theme by remember {
                mutableStateOf(runCatching { ThemeChoice.valueOf(prefs.getString("theme", "AUTO") ?: "AUTO") }.getOrDefault(ThemeChoice.AUTO))
            }
            PrivacyTheme(theme) {
                PrivacyGuardScreen(theme, onTheme = {
                    theme = it
                    prefs.edit().putString("theme", it.name).apply()
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyGuardScreen(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.packageManager
    val uiPrefs = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }
    val policyPrefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val profilePrefs = remember { context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE) }

    var screen by remember { mutableStateOf(Screen.APPS) }
    var showSystemApps by remember { mutableStateOf(uiPrefs.getBoolean("show_system_apps", false)) }
    var menu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var selectedProfile by remember { mutableStateOf<String?>(null) }
    val enabledMap = remember { mutableStateMapOf<String, Boolean>() }
    var profileVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        policyPrefs.all.keys.filter { it.endsWith(".enabled") }.forEach { key ->
            enabledMap[key.removeSuffix(".enabled")] = policyPrefs.getBoolean(key, false)
        }
    }

    val apps by produceState<List<AppItem>>(initialValue = emptyList(), key1 = pm) {
        value = withContext(Dispatchers.IO) {
            val iconSize = (48 * context.resources.displayMetrics.density).toInt().coerceIn(96, 160)
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName }
                .map { info ->
                    AppItem(
                        label = pm.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        icon = runCatching { drawableToBitmap(pm.getApplicationIcon(info), iconSize) }.getOrNull()
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    if (selectedPackage != null) {
        BackHandler { selectedPackage = null; screen = Screen.APPS }
        AppPolicyScreen(selectedPackage!!, pm, onBack = { selectedPackage = null; screen = Screen.APPS })
        return
    }
    if (screen == Screen.PROFILE_EDIT && selectedProfile != null) {
        BackHandler { selectedProfile = null; screen = Screen.PROFILES }
        ProfileEditorScreen(
            profileId = selectedProfile!!,
            prefs = profilePrefs,
            onBack = { selectedProfile = null; screen = Screen.PROFILES; profileVersion++ }
        )
        return
    }
    if (screen == Screen.PROFILE_APPS && selectedProfile != null) {
        BackHandler { selectedProfile = null; screen = Screen.PROFILES }
        ProfileAppPicker(
            profile = ProfileStore.load(profilePrefs, selectedProfile!!),
            apps = apps,
            enabledMap = enabledMap,
            policyPrefs = policyPrefs,
            profilePrefs = profilePrefs,
            onBack = { selectedProfile = null; screen = Screen.PROFILES; profileVersion++ }
        )
        return
    }
    if (screen == Screen.PROFILES) {
        BackHandler { screen = Screen.APPS }
        ProfilesScreen(
            prefs = profilePrefs,
            version = profileVersion,
            onBack = { screen = Screen.APPS },
            onEdit = { selectedProfile = it; screen = Screen.PROFILE_EDIT },
            onApply = { selectedProfile = it; screen = Screen.PROFILE_APPS }
        )
        return
    }
    if (screen == Screen.SETTINGS) {
        BackHandler { screen = Screen.APPS }
        Scaffold(topBar = {
            TopAppBar(title = { Text("الإعدادات") }, navigationIcon = {
                IconButton(onClick = { screen = Screen.APPS }) { Icon(Icons.Default.ArrowBack, "رجوع") }
            })
        }) { padding -> GlobalSettings(theme, onTheme, Modifier.padding(padding)) }
        return
    }

    val query = search.trim().lowercase()
    val shown = apps.asSequence()
        .filter { !it.isSystem || showSystemApps || enabledMap[it.packageName] == true }
        .filter { query.isEmpty() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
        .sortedWith(compareByDescending<AppItem> { enabledMap[it.packageName] == true }.thenBy { it.label.lowercase() })
        .toList()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("التطبيقات") },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "المزيد") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (showSystemApps) "إخفاء تطبيقات النظام" else "إظهار تطبيقات النظام") },
                        onClick = {
                            showSystemApps = !showSystemApps
                            uiPrefs.edit().putBoolean("show_system_apps", showSystemApps).apply()
                            menu = false
                        }
                    )
                    DropdownMenuItem(text = { Text("البروفايلات المخصصة") }, onClick = { screen = Screen.PROFILES; menu = false })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("الإعدادات") }, onClick = { screen = Screen.SETTINGS; menu = false })
                }
            }
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, "بحث") },
                placeholder = { Text("ابحث باسم التطبيق أو اسم الحزمة") },
                shape = RoundedCornerShape(14.dp)
            )
            if (apps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("جاري تحميل التطبيقات…")
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.packageName }, contentType = { "app" }) { app ->
                        AppRow(
                            app = app,
                            enabled = enabledMap[app.packageName] == true,
                            onClick = { selectedPackage = app.packageName; screen = Screen.POLICY },
                            onEnabledChange = { enabled ->
                                enabledMap[app.packageName] = enabled
                                policyPrefs.edit().putBoolean("${app.packageName}.enabled", enabled).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap
}

@Composable
private fun AppRow(app: AppItem, enabled: Boolean, onClick: () -> Unit, onEnabledChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            if (app.icon != null) Image(app.icon.asImageBitmap(), contentDescription = app.label, modifier = Modifier.size(48.dp))
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

@Composable
private fun GlobalSettings(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("المظهر", style = MaterialTheme.typography.titleLarge)
        listOf(ThemeChoice.AUTO to "تلقائي", ThemeChoice.LIGHT to "نهاري", ThemeChoice.AMOLED to "AMOLED").forEach { (value, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                RadioButton(selected = theme == value, onClick = { onTheme(value) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilesScreen(
    prefs: android.content.SharedPreferences,
    version: Int,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onApply: (String) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var localVersion by remember(version) { mutableIntStateOf(version) }
    val ids = remember(localVersion) { ProfileStore.ids(prefs) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("البروفايلات المخصصة") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
            actions = { IconButton(onClick = { newName = ""; showCreate = true }) { Icon(Icons.Default.Add, "إنشاء") } }
        )
    }) { padding ->
        if (ids.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد بروفايلات. اضغط + لإنشاء أول بروفايل.")
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(ids, key = { it }) { id ->
                    val profile = ProfileStore.load(prefs, id)
                    ListItem(
                        modifier = Modifier.clickable { onEdit(id) },
                        headlineContent = { Text(profile.name) },
                        supportingContent = { Text("${profile.rules.size} سياسات • ${profile.paths.size} مجلدات • ${profile.assignedApps.size} تطبيقات") },
                        trailingContent = { Button(onClick = { onApply(id) }) { Text("تطبيق") } }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("إنشاء بروفايل مخصص") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("اسم البروفايل") }) },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty()) {
                        val id = UUID.randomUUID().toString()
                        ProfileStore.save(prefs, PrivacyProfile(id, name, emptySet(), emptyList()))
                        localVersion++
                        showCreate = false
                    }
                }) { Text("إنشاء") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("إلغاء") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorScreen(
    profileId: String,
    prefs: android.content.SharedPreferences,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initial = remember(profileId) { ProfileStore.load(prefs, profileId) }
    var name by remember(profileId) { mutableStateOf(initial.name) }
    var rules by remember(profileId) { mutableStateOf(initial.rules) }
    var paths by remember(profileId) { mutableStateOf(initial.paths) }
    var newPath by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }

    fun save() {
        val clean = name.trim().ifEmpty { initial.name }
        val updated = initial.copy(name = clean, rules = rules, paths = paths)
        ProfileStore.save(prefs, updated)
        val policyPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = policyPrefs.edit()
        updated.assignedApps.forEach { pkg ->
            e.putBoolean("$pkg.enabled", true)
            PrivacyRule.values().forEach { rule ->
                e.putBoolean("$pkg.${rule.name}", rule in updated.rules)
            }
            e.putStringSet("$pkg.custom_paths", updated.paths.toSet())
        }
        e.apply()
        onBack()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("تعديل البروفايل") },
            navigationIcon = { IconButton(onClick = { save() }) { Icon(Icons.Default.ArrowBack, "حفظ ورجوع") } },
            actions = {
                IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, "حذف البروفايل") }
            }
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true, label = { Text("اسم البروفايل") })
                Text("السياسات", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(PrivacyRule.values().toList()) { rule ->
                val checked = rule in rules
                ListItem(
                    headlineContent = { Text(rule.title) },
                    supportingContent = { Text(if (checked) "سيتم حجبها" else "مسموح") },
                    trailingContent = { Switch(checked = checked, onCheckedChange = { rules = if (it) rules + rule else rules - rule }) }
                )
            }
            item {
                Text("المجلدات المحجوبة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                paths.forEach { path ->
                    ListItem(headlineContent = { Text(path, maxLines = 2) }, trailingContent = {
                        TextButton(onClick = { paths = paths.filterNot { it == path } }) { Text("حذف") }
                    })
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newPath, { newPath = it }, Modifier.weight(1f), singleLine = true, label = { Text("مسار مجلد") })
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val clean = newPath.trim().trimEnd('/')
                        if (clean.isNotEmpty() && clean !in paths) { paths = paths + clean; newPath = "" }
                    }) { Text("إضافة") }
                }
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("حفظ البروفايل") }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("حذف البروفايل؟") },
            text = { Text("سيتم حذف إعدادات هذا البروفايل وربطه بالتطبيقات.") },
            confirmButton = {
                TextButton(onClick = { ProfileStore.delete(prefs, profileId); onBack() }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("إلغاء") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileAppPicker(
    profile: PrivacyProfile,
    apps: List<AppItem>,
    enabledMap: MutableMap<String, Boolean>,
    policyPrefs: android.content.SharedPreferences,
    profilePrefs: android.content.SharedPreferences,
    onBack: () -> Unit
) {
    val selected = remember(profile.id) { mutableStateListOf<String>().also { it.addAll(profile.assignedApps) } }
    var search by remember { mutableStateOf("") }
    val query = search.trim().lowercase()
    val shown = apps.filter { query.isEmpty() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
        .sortedWith(compareByDescending<AppItem> { it.packageName in selected }.thenBy { it.label.lowercase() })

    fun toggle(pkg: String, value: Boolean) {
        if (value) { if (pkg !in selected) selected.add(pkg) } else selected.remove(pkg)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("تطبيق البروفايل") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
            actions = { Text("${selected.size}", modifier = Modifier.padding(horizontal = 12.dp)) }
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text("${selected.size} تطبيقات مفعّل عليها البروفايل", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, "بحث") }, placeholder = { Text("ابحث باسم التطبيق أو اسم الحزمة") })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { shown.forEach { toggle(it.packageName, true) } }) { Text("تحديد الظاهر") }
                TextButton(onClick = { shown.forEach { toggle(it.packageName, false) } }) { Text("إلغاء الظاهر") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(shown, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    ListItem(
                        modifier = Modifier.clickable { toggle(app.packageName, !checked) },
                        leadingContent = { app.icon?.let { Image(it.asImageBitmap(), app.label, Modifier.size(48.dp)) } },
                        headlineContent = { Text(app.label, maxLines = 1) },
                        supportingContent = { Text(app.packageName, maxLines = 1) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (app.isSystem) AssistChip(onClick = {}, label = { Text("نظام") })
                                Spacer(Modifier.width(8.dp))
                                Checkbox(checked = checked, onCheckedChange = { value -> toggle(app.packageName, value) })
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
            Button(
                onClick = {
                    val assigned = selected.toSet()
                    val oldAssigned = profile.assignedApps
                    val e = policyPrefs.edit()
                    selected.distinct().forEach { pkg ->
                        e.putBoolean("$pkg.enabled", true)
                        PrivacyRule.values().forEach { rule -> e.putBoolean("$pkg.${rule.name}", rule in profile.rules) }
                        e.putStringSet("$pkg.custom_paths", profile.paths.toSet())
                        enabledMap[pkg] = true
                    }
                    (oldAssigned - assigned).forEach { pkg ->
                        // Removing an app from the profile also disables its protection if this profile owned it.
                        e.putBoolean("$pkg.enabled", false)
                        enabledMap[pkg] = false
                    }
                    e.apply()
                    ProfileStore.save(profilePrefs, profile.copy(assignedApps = assigned))
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("حفظ وتطبيق على ${selected.size} تطبيق") }
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
    var paths by remember(packageName) { mutableStateOf(prefs.getStringSet("$packageName.custom_paths", emptySet())?.toList() ?: emptyList()) }
    var newPath by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("إعدادات $appName") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text("تفعيل حماية هذا التطبيق") },
                    supportingContent = { Text(if (protectionEnabled) "الحماية مفعّلة" else "الحماية غير مفعّلة") },
                    trailingContent = {
                        Switch(checked = protectionEnabled, onCheckedChange = {
                            protectionEnabled = it
                            prefs.edit().putBoolean("$packageName.enabled", it).apply()
                        })
                    }
                )
                HorizontalDivider()
                Text("ما الذي تريد حجبه؟", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
            items(PrivacyRule.values().toList()) { rule ->
                val checked = rules[rule] == true
                ListItem(
                    headlineContent = { Text(rule.title) },
                    supportingContent = { Text(if (checked) "محجوب" else "مسموح") },
                    trailingContent = {
                        Switch(checked = checked, onCheckedChange = { value ->
                            rules = rules.toMutableMap().apply { put(rule, value) }
                            prefs.edit().putBoolean("$packageName.${rule.name}", value).apply()
                        })
                    }
                )
            }
            item {
                Text("المجلدات المحجوبة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
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
                        if (clean.isNotEmpty() && clean !in paths) {
                            paths = paths + clean
                            prefs.edit().putStringSet("$packageName.custom_paths", paths.toSet()).apply()
                            newPath = ""
                        }
                    }) { Text("إضافة") }
                }
            }
        }
    }
}
