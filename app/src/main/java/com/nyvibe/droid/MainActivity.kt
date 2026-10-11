package com.nyvibe.droid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.net.Uri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AppCanvas = Color(0xFF0B1018)
private val AppPanel = Color(0xFF151D2A)
private val AppAccent = Color(0xFFB7F56A)
private val AppText = Color(0xFFF2F5F9)
private val AppMuted = Color(0xFF9CAABD)

private enum class AppSection(val label: String, val symbol: String) {
    WORKSPACE("Workspace", "⌂"),
    GIT("Git", "⑂"),
    BUILDS("Remote Build", "↗")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AppAccent,
                    onPrimary = AppCanvas,
                    background = AppCanvas,
                    onBackground = AppText,
                    surface = AppPanel,
                    onSurface = AppText,
                    secondary = Color(0xFF84BBFF),
                    error = Color(0xFFFF8989)
                )
            ) {
                var selectedSection by remember { mutableStateOf(AppSection.WORKSPACE) }
                Surface(modifier = Modifier.fillMaxSize(), color = AppCanvas) {
                    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppCanvas)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AppSection.values().forEach { section ->
                                val selected = selectedSection == section
                                Surface(
                                    color = if (selected) AppAccent else AppPanel,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedSection = section }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            section.symbol,
                                            color = if (selected) AppCanvas else AppAccent,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                        Spacer(Modifier.padding(horizontal = 4.dp))
                                        Text(
                                            section.label,
                                            color = if (selected) AppCanvas else AppMuted,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            when (selectedSection) {
                                AppSection.WORKSPACE -> WorkspaceScreen()
                                AppSection.GIT -> GitRepositoryScreen { uri ->
                                    WorkspaceRepository.saveRootUri(this@MainActivity, Uri.parse(uri))
                                    selectedSection = AppSection.WORKSPACE
                                }
                                AppSection.BUILDS -> RemoteBuildScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
