package com.privacyguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Privacy Guard", style = MaterialTheme.typography.headlineMedium)
                        Text("Kotlin + Jetpack Compose")
                        Text("Modern LibXposed module baseline.")
                        Text("فعّل الموديول وحدد التطبيق المستهدف من LSPosed Manager.")
                    }
                }
            }
        }
    }
}
