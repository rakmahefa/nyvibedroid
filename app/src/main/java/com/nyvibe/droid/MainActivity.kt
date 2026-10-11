package com.nyvibe.droid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFB7F56A),
                    onPrimary = Color(0xFF0B1018),
                    background = Color(0xFF0B1018),
                    onBackground = Color(0xFFF2F5F9),
                    surface = Color(0xFF151D2A),
                    onSurface = Color(0xFFF2F5F9),
                    secondary = Color(0xFF84BBFF),
                    error = Color(0xFFFF8989)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B1018)) {
                    RemoteBuildScreen()
                }
            }
        }
    }
}
