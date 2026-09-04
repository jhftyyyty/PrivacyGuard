package com.privacyguard

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeChoice { AUTO, LIGHT, AMOLED }
enum class AppFilter { ALL, USER, SYSTEM }

@Composable
fun PrivacyTheme(choice:ThemeChoice,content:@Composable()->Unit){
 val dark=when(choice){ThemeChoice.AUTO->isSystemInDarkTheme();ThemeChoice.LIGHT->false;ThemeChoice.AMOLED->true}
 val scheme=if(dark) darkColorScheme(background=if(choice==ThemeChoice.AMOLED) Color.Black else Color(0xFF121212),surface=if(choice==ThemeChoice.AMOLED) Color.Black else Color(0xFF121212)) else lightColorScheme()
 MaterialTheme(colorScheme=scheme,content=content)
}

class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  setContent {
   var theme by remember{mutableStateOf(ThemeChoice.AUTO)}
   PrivacyTheme(theme){ PrivacyGuardScreen(theme){theme=it} }
  }
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyGuardScreen(theme:ThemeChoice,onTheme:(ThemeChoice)->Unit){
 val context=androidx.compose.ui.platform.LocalContext.current
 val pm=context.packageManager
 val apps=remember{pm.getInstalledApplications(PackageManager.GET_META_DATA).filter{it.packageName!=context.packageName}.sortedBy{pm.getApplicationLabel(it).toString().lowercase()}}
 var filter by remember{mutableStateOf(AppFilter.ALL)}
 var menu by remember{mutableStateOf(false)}
 var settings by remember{mutableStateOf(false)}
 Scaffold(topBar={
  TopAppBar(
   title={Text(if(settings)"الإعدادات" else "التطبيقات")},
   navigationIcon={},
   actions={
    if(!settings){
     IconButton(onClick={menu=true}){Icon(Icons.Default.MoreVert,contentDescription="القائمة")}
     DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
      DropdownMenuItem(text={Text("إظهار كل التطبيقات")},onClick={filter=AppFilter.ALL;menu=false})
      DropdownMenuItem(text={Text("إظهار التطبيقات العادية فقط")},onClick={filter=AppFilter.USER;menu=false})
      DropdownMenuItem(text={Text("إظهار تطبيقات النظام فقط")},onClick={filter=AppFilter.SYSTEM;menu=false})
     }
    }
    else IconButton(onClick={settings=false}){Text("رجوع")}
   }
  )
 }){padding->
  if(settings){
   Column(Modifier.padding(padding).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text("المظهر",style=MaterialTheme.typography.titleLarge)
    Text("الوضع الافتراضي: تلقائي")
    listOf(ThemeChoice.AUTO to "تلقائي",ThemeChoice.LIGHT to "نهاري",ThemeChoice.AMOLED to "AMOLED").forEach{(v,label)->
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
      Text(label);RadioButton(selected=theme==v,onClick={onTheme(v)})
     }
    }
   }
  } else {
   Column(Modifier.padding(padding)){
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.End){
     TextButton(onClick={settings=true}){Text("الإعدادات")}
    }
    val shown=apps.filter{when(filter){AppFilter.ALL->true;AppFilter.USER->it.flags and ApplicationInfo.FLAG_SYSTEM==0;AppFilter.SYSTEM->it.flags and ApplicationInfo.FLAG_SYSTEM!=0}}
    LazyColumn{items(shown,key={it.packageName}){app->
     ListItem(
      headlineContent={Text(pm.getApplicationLabel(app).toString())},
      supportingContent={Text(app.packageName)},
      trailingContent={if(app.flags and ApplicationInfo.FLAG_SYSTEM!=0){AssistChip(onClick={},label={Text("نظام")})}else null}
     )
    }}
   }
  }
 }
}
