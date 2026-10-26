package com.achooluv.nikonlink.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achooluv.nikonlink.NikonLinkRepository

private data class Tab(val label: String, val icon: @Composable () -> Unit)

@Composable
fun AppRoot(repo: NikonLinkRepository) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var showTutorial by rememberSaveable { mutableStateOf(false) }

    // Back / gesture navigation closes the tutorial first; only a second back
    // leaves the app.
    BackHandler(enabled = showTutorial) { showTutorial = false }

    // Keep the screen awake while bytes are moving; release it as soon as the
    // batch ends so the phone can sleep again.
    val progress by repo.progress.collectAsStateWithLifecycle()
    val connectMode by repo.settings.mode.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    LaunchedEffect(progress.active) {
        val window = activity?.window ?: return@LaunchedEffect
        if (progress.active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val tabs = listOf(
        Tab("连接") { Icon(Icons.Rounded.Link, contentDescription = null) },
        Tab("浏览") { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
        Tab("设置") { Icon(Icons.Rounded.Settings, contentDescription = null) },
    )

    Box(Modifier.fillMaxSize().background(Ink)) {
        Scaffold(
            containerColor = Ink,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar(
                    containerColor = InkRaised,
                    tonalElevation = 0.dp,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = tabIndex == index,
                            onClick = { tabIndex = index },
                            icon = tab.icon,
                            label = { Text(tab.label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF161200),
                                selectedTextColor = NikonYellow,
                                indicatorColor = NikonYellow,
                                unselectedIconColor = PaperDim,
                                unselectedTextColor = PaperDim,
                            ),
                        )
                    }
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner).statusBarsPadding()) {
                when (tabIndex) {
                    0 -> ConnectScreen(
                        repo = repo,
                        onTutorial = { showTutorial = true },
                        onBrowse = { tabIndex = 1 },
                    )
                    1 -> BrowseScreen(repo)
                    else -> SettingsScreen(repo)
                }
            }
        }

        // Full-screen overlay: the tutorial owns its own header/insets. It opens
        // on the current connection mode but its own switch is documentation-only.
        AnimatedVisibility(
            visible = showTutorial,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { height -> height / 14 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { height -> height / 14 },
        ) {
            TutorialScreen(
                initialMode = connectMode,
                onDismiss = { showTutorial = false },
            )
        }
    }
}
