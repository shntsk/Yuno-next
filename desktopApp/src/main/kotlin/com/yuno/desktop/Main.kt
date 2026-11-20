package com.yuno.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yuno.desktop.account.DesktopAccount
import com.yuno.desktop.data.DesktopLibrary
import com.yuno.desktop.model.DesktopVideo
import com.yuno.desktop.player.DesktopPlayer
import com.yuno.desktop.security.DesktopKeyring
import com.yuno.desktop.youtube.DesktopVideoDetails
import com.yuno.desktop.youtube.DesktopYouTube
import com.yuno.icons.YunoIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.text.NumberFormat
import java.util.Locale

private val pageBackground = Color(0xFF101014)
private val panelBackground = Color(0xFF1B1B22)
private val accent = Color(0xFFC4B2FF)
private val accentSoft = Color(0xFF302A42)

private enum class Page { Home, Search, Library, Settings, Details }

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Yuno",
        state = rememberWindowState(width = 1220.dp, height = 790.dp),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = accent,
                onPrimary = Color(0xFF231A39),
                secondary = Color(0xFFB6C5FF),
                background = pageBackground,
                surface = panelBackground,
                surfaceVariant = Color(0xFF25252D),
                onSurface = Color(0xFFF3F0F7),
                onSurfaceVariant = Color(0xFFB8B3C2),
                outline = Color(0xFF45414E),
            ),
        ) {
            YunoDesktop()
        }
    }
}

@Composable
private fun YunoDesktop() {
    var page by remember { mutableStateOf(Page.Home) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<DesktopVideo?>(null) }
    var details by remember { mutableStateOf<DesktopVideoDetails?>(null) }
    val results = remember { mutableStateListOf<DesktopVideo>() }
    val library = remember { mutableStateListOf<DesktopVideo>().apply { addAll(DesktopLibrary.savedVideos()) } }
    val scope = rememberCoroutineScope()

    fun search() {
        val term = query.trim()
        if (term.isEmpty()) return
        page = Page.Search
        loading = true
        message = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { DesktopYouTube.search(term) } }
                .onSuccess {
                    results.clear()
                    results.addAll(it)
                    DesktopLibrary.rememberSearch(term)
                    if (it.isEmpty()) message = "No videos found."
                }
                .onFailure { message = it.message ?: "Search failed." }
            loading = false
        }
    }

    fun openVideo(video: DesktopVideo) {
        selected = video
        details = null
        message = null
        page = Page.Details
        loading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { DesktopYouTube.details(video) } }
                .onSuccess { details = it }
                .onFailure { message = it.message ?: "Could not load this video." }
            loading = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = pageBackground) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                containerColor = pageBackground,
                header = {
                    Box(
                        Modifier.padding(top = 22.dp, bottom = 28.dp).size(42.dp)
                            .clip(RoundedCornerShape(15.dp)).background(accentSoft),
                        contentAlignment = Alignment.Center,
                    ) { Text("Y", color = accent, fontSize = 23.sp, fontWeight = FontWeight.Black) }
                },
            ) {
                NavigationRailItem(
                    selected = page == Page.Home,
                    onClick = { page = Page.Home; message = null },
                    icon = { Icon(YunoIcons.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    alwaysShowLabel = true,
                )
                NavigationRailItem(
                    selected = page == Page.Search || page == Page.Details,
                    onClick = { page = Page.Search; message = null },
                    icon = { Icon(YunoIcons.TabSearch, contentDescription = "Search") },
                    label = { Text("Search") },
                    alwaysShowLabel = true,
                )
                NavigationRailItem(
                    selected = page == Page.Library,
                    onClick = { page = Page.Library; message = null },
                    icon = { Icon(YunoIcons.TabLibrary, contentDescription = "Library") },
                    label = { Text("Library") },
                    alwaysShowLabel = true,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { page = Page.Settings; message = null },
                    modifier = Modifier.padding(bottom = 16.dp).size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = if (page == Page.Settings) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (page == Page.Details) {
                        IconButton(onClick = { page = Page.Search }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Text(
                        when (page) {
                            Page.Home -> "Yuno"
                            Page.Search -> "Search"
                            Page.Library -> "Your library"
                            Page.Settings -> "Settings"
                            Page.Details -> "Now exploring"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (page != Page.Settings) {
                        FilledTonalButton(onClick = { page = Page.Search }) {
                            Icon(YunoIcons.Search, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Find something")
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF292832))
                Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp)) {
                    when (page) {
                        Page.Home -> HomePage(
                            searches = DesktopLibrary.recentSearches(),
                            onSearch = { query = it; search() },
                            onExplore = { page = Page.Search },
                        )
                        Page.Search -> SearchPage(
                            query = query,
                            onQueryChange = { query = it },
                            loading = loading,
                            message = message,
                            results = results,
                            onSearch = ::search,
                            onOpen = ::openVideo,
                        )
                        Page.Library -> LibraryPage(
                            videos = library,
                            onOpen = ::openVideo,
                            onRemove = {
                                DesktopLibrary.remove(it)
                                library.remove(it)
                            },
                        )
                        Page.Settings -> SettingsPage(onStatus = { message = it })
                        Page.Details -> VideoDetailsPage(
                            video = selected,
                            details = details,
                            loading = loading,
                            message = message,
                            isSaved = selected?.let { DesktopLibrary.isSaved(it) } == true,
                            onPlay = {
                                details?.let { current ->
                                    runCatching { DesktopPlayer.play(current) }
                                        .onSuccess { message = "Playing in mpv." }
                                        .onFailure { message = it.message ?: "Playback could not be started." }
                                }
                            },
                            onSave = {
                                val item = details?.video ?: selected
                                if (item != null) {
                                    if (DesktopLibrary.isSaved(item)) {
                                        DesktopLibrary.remove(item)
                                        library.removeAll { it.id == item.id }
                                        message = "Removed from your library."
                                    } else {
                                        DesktopLibrary.save(item)
                                        library.removeAll { it.id == item.id }
                                        library.add(0, item)
                                        message = "Saved to your library."
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePage(searches: List<String>, onSearch: (String) -> Unit, onExplore: () -> Unit) {
    Column(Modifier.fillMaxSize().widthIn(max = 980.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A quieter way to watch.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Find videos, follow the things you love and keep your library close.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            onClick = onExplore,
            modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp),
            colors = CardDefaults.cardColors(containerColor = accentSoft),
            shape = RoundedCornerShape(24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(YunoIcons.Play, null, tint = accent, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Start exploring", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Search YouTube without the noise.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(YunoIcons.Search, null, tint = accent)
            }
        }
        if (searches.isNotEmpty()) {
            Text("Recent searches", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                searches.take(8).forEach { term ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onSearch(term) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(YunoIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(term, Modifier.weight(1f))
                        Icon(Icons.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPage(
    query: String,
    onQueryChange: (String) -> Unit,
    loading: Boolean,
    message: String?,
    results: List<DesktopVideo>,
    onSearch: () -> Unit,
    onOpen: (DesktopVideo) -> Unit,
) {
    Column(Modifier.fillMaxSize().widthIn(max = 1120.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search videos, channels and music") },
            leadingIcon = { Icon(YunoIcons.Search, null) },
            trailingIcon = {
                Button(onClick = onSearch, enabled = query.isNotBlank() && !loading) { Text("Search") }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Videos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        when {
            message != null -> Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            results.isEmpty() && !loading -> Text("Your search results will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.id }) { video -> VideoRow(video, onClick = { onOpen(video) }) }
            }
        }
    }
}

@Composable
private fun LibraryPage(
    videos: List<DesktopVideo>,
    onOpen: (DesktopVideo) -> Unit,
    onRemove: (DesktopVideo) -> Unit,
) {
    Column(Modifier.fillMaxSize().widthIn(max = 1120.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Saved videos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (videos.isEmpty()) {
            Text("Videos you save will stay here on this computer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(videos, key = { it.id }) {
                    VideoRow(it, onClick = { onOpen(it) }, onRemove = { onRemove(it) })
                }
            }
        }
    }
}

@Composable
private fun VideoRow(video: DesktopVideo, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = panelBackground),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(video.thumbnail, Modifier.width(190.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(video.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(video.channel, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(
                    listOfNotNull(
                        video.durationSeconds.takeIf { it > 0 }?.let(::formatDuration),
                        video.views.takeIf { it > 0 }?.let(::formatViews),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun VideoDetailsPage(
    video: DesktopVideo?,
    details: DesktopVideoDetails?,
    loading: Boolean,
    message: String?,
    isSaved: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxSize().widthIn(max = 980.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (loading) {
            CircularProgressIndicator()
            Text("Getting the best available stream…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val current = details?.video ?: video
        if (current != null) {
            Thumbnail(current.thumbnail, Modifier.fillMaxWidth().widthIn(max = 820.dp).aspectRatio(16f / 9f))
            Text(current.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "${current.channel}  ·  ${formatViews(current.views)} views",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlay, enabled = details?.videoUrl != null) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play in mpv")
                }
                OutlinedButton(onClick = onSave) {
                    Icon(if (isSaved) YunoIcons.Check else Icons.Rounded.Bookmark, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isSaved) "Saved" else "Save")
                }
                details?.quality?.let {
                    Text(it, Modifier.align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!details?.description.isNullOrBlank()) {
                Text("Description", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(details?.description.orEmpty().take(1800), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsPage(onStatus: (String) -> Unit) {
    var keyringAvailable by remember { mutableStateOf<Boolean?>(null) }
    var signedIn by remember { mutableStateOf(false) }
    var cookieDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            keyringAvailable = DesktopKeyring.isAvailable()
            signedIn = runCatching { DesktopAccount.isSignedIn() }.getOrDefault(false)
        }
    }

    Column(Modifier.fillMaxSize().widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = panelBackground), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (signedIn) "YouTube session connected" else "Connect a YouTube session", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (signedIn) "Your session is stored in the operating system keyring."
                            else "Optional. Public videos and search work without signing in.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (signedIn) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    runCatching { withContext(Dispatchers.IO) { DesktopAccount.signOut() } }
                                        .onSuccess {
                                            signedIn = false
                                            cookieDraft = ""
                                            error = null
                                            onStatus("Signed out.")
                                        }
                                        .onFailure { error = it.message ?: "Could not clear the saved session." }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                        ) { Text("Sign out") }
                    }
                }
                if (!signedIn) {
                    Text(
                        "Sign in to YouTube in your browser. Copy the Cookie request header from a signed-in youtube.com request in Developer Tools and paste it below. Yuno checks the session cookies before saving them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = cookieDraft,
                        onValueChange = { cookieDraft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                        label = { Text("YouTube Cookie header") },
                        placeholder = { Text("SAPISID=…; LOGIN_INFO=…") },
                        visualTransformation = PasswordVisualTransformation(),
                        minLines = 3,
                        maxLines = 5,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        check(DesktopKeyring.isAvailable()) {
                                            "The system keyring is unavailable. Install and unlock your Secret Service provider first."
                                        }
                                        DesktopAccount.importCookieHeader(cookieDraft)
                                    }
                                }.onSuccess {
                                    signedIn = true
                                    cookieDraft = ""
                                    onStatus("YouTube session saved securely.")
                                }.onFailure { error = it.message ?: "The session could not be saved." }
                                busy = false
                            }
                        },
                        enabled = !busy && cookieDraft.isNotBlank() && keyringAvailable == true,
                    ) { Text(if (busy) "Saving…" else "Save session securely") }
                }
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        }

        Text("Security", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = panelBackground), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("System keyring", fontWeight = FontWeight.SemiBold)
                Text(
                    when (keyringAvailable) {
                        true -> if (System.getProperty("os.name").startsWith("Windows", true))
                            "Windows Credential Manager detected."
                        else "Secret Service client detected. GNOME Keyring, KWallet and compatible providers are supported."
                        false -> "secret-tool was not found. On Linux, install libsecret tools and unlock your keyring."
                        null -> "Checking keyring availability…"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Session cookies are never written to Yuno preferences or a plain-text file. If the system keyring is unavailable, session saving stays disabled.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("Playback", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = panelBackground), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("External player", fontWeight = FontWeight.SemiBold)
                Text(
                    "Yuno uses mpv for desktop playback. Install mpv and make sure the mpv executable is available in PATH.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    runCatching { Desktop.getDesktop().browse(URI("https://mpv.io/installation/")) }
                }) {
                    Text("mpv installation")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(url: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) null
            else runCatching {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                try {
                    connection.inputStream.use { input ->
                        org.jetbrains.skia.Image.makeFromEncoded(input.readBytes()).toComposeImageBitmap()
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF292832)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(YunoIcons.Play, null, tint = accent.copy(alpha = 0.8f))
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}

private fun formatViews(views: Long): String = when {
    views >= 1_000_000_000 -> "%.1fB".format(Locale.ROOT, views / 1_000_000_000.0)
    views >= 1_000_000 -> "%.1fM".format(Locale.ROOT, views / 1_000_000.0)
    views >= 1_000 -> "%.1fK".format(Locale.ROOT, views / 1_000.0)
    else -> NumberFormat.getIntegerInstance().format(views)
}
