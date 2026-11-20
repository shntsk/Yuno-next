package com.yuno.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

private enum class Destination(val label: String) {
    Home("Home"), Search("Search"), Library("Library"), Settings("Settings")
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Yuno",
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            YunoDesktop()
        }
    }
}

@Composable
private fun YunoDesktop() {
    var destination by remember { mutableStateOf(Destination.Home) }
    Surface(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                header = {
                    Text("Yuno", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 24.dp))
                },
            ) {
                Destination.entries.forEach { item ->
                    val icon = when (item) {
                        Destination.Home -> Icons.Rounded.Home
                        Destination.Search -> Icons.Rounded.Search
                        Destination.Library -> Icons.Rounded.LibraryMusic
                        Destination.Settings -> Icons.Rounded.Settings
                    }
                    NavigationRailItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
            ) {
                when (destination) {
                    Destination.Home -> PlaceholderPage("Home", "Your recommendations will appear here.")
                    Destination.Search -> PlaceholderPage("Search", "Find videos, channels and playlists.")
                    Destination.Library -> PlaceholderPage("Library", "Your saved content will appear here.")
                    Destination.Settings -> PlaceholderPage("Settings", "Desktop preferences belong here.")
                }
            }
        }
    }
}

@Composable
private fun PlaceholderPage(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
