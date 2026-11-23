package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.i18n.AppLocalization
import com.example.ui.ProcessingUiState
import com.example.ui.VocalStudioViewModel
import com.example.ui.VoiceEnhanceUiState
import com.example.ui.VoiceEnhancerViewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ProcessingScreen
import com.example.ui.screens.ResultsScreen
import com.example.ui.screens.SettingsBottomSheet
import com.example.ui.screens.VoiceEnhancerScreen
import com.example.ui.theme.VocalStudioTheme

private const val ROUTE_HOME = "home"
private const val ROUTE_PROCESSING = "processing"
private const val ROUTE_RESULTS = "results/{trackId}"

private enum class StudioMainTab {
    VOCAL_REMOVER,
    VOICE_ENHANCER
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VocalRemoverStudioApp()
        }
    }
}

@Composable
fun VocalRemoverStudioApp(
    viewModel: VocalStudioViewModel = viewModel(),
    voiceEnhancerViewModel: VoiceEnhancerViewModel = viewModel()
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val processingState by viewModel.processingState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val storageBytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val serverTestResult by viewModel.serverTestState.collectAsStateWithLifecycle()

    // Independent AI Voice Enhancer state
    val veStagedInput by voiceEnhancerViewModel.stagedInput.collectAsStateWithLifecycle()
    val veStrength by voiceEnhancerViewModel.selectedStrength.collectAsStateWithLifecycle()
    val veProcessingState by voiceEnhancerViewModel.processingState.collectAsStateWithLifecycle()
    val veActiveResultId by voiceEnhancerViewModel.activeResultId.collectAsStateWithLifecycle()
    val veHistory by voiceEnhancerViewModel.enhancedHistory.collectAsStateWithLifecycle()
    val vePlaybackState by voiceEnhancerViewModel.playbackState.collectAsStateWithLifecycle()
    val veExportState by voiceEnhancerViewModel.exportState.collectAsStateWithLifecycle()

    val veActiveResult = remember(veActiveResultId, veHistory) {
        veHistory.firstOrNull { it.id == veActiveResultId }
    }

    val strings = AppLocalization.forLanguage(prefs.language)
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(StudioMainTab.VOCAL_REMOVER) }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        voiceEnhancerViewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(processingState) {
        when (val state = processingState) {
            is ProcessingUiState.Running -> {
                if (navController.currentDestination?.route != ROUTE_PROCESSING) {
                    navController.navigate(ROUTE_PROCESSING) {
                        launchSingleTop = true
                    }
                }
            }
            is ProcessingUiState.Completed -> {
                val trackId = state.trackId
                viewModel.resetProcessingState()
                navController.navigate("results/$trackId") {
                    popUpTo(ROUTE_HOME) { inclusive = false }
                    launchSingleTop = true
                }
            }
            is ProcessingUiState.Idle -> {
                if (navController.currentDestination?.route == ROUTE_PROCESSING) {
                    navController.popBackStack(ROUTE_HOME, inclusive = false)
                }
            }
            is ProcessingUiState.Error -> Unit
        }
    }

    val isVocalRunning = processingState is ProcessingUiState.Running
    val isVoiceRunning = veProcessingState is VoiceEnhanceUiState.Running

    VocalStudioTheme(themeMode = prefs.themeMode) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 720.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == StudioMainTab.VOCAL_REMOVER,
                                onClick = {
                                    voiceEnhancerViewModel.pausePlayback()
                                    selectedTab = StudioMainTab.VOCAL_REMOVER
                                },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (isVocalRunning) Badge()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (selectedTab == StudioMainTab.VOCAL_REMOVER) {
                                                Icons.Rounded.GraphicEq
                                            } else {
                                                Icons.Outlined.GraphicEq
                                            },
                                            contentDescription = strings.navVocalRemover
                                        )
                                    }
                                },
                                label = { Text(strings.navVocalRemover) },
                                modifier = Modifier.testTag("nav_vocal_remover")
                            )

                            NavigationBarItem(
                                selected = selectedTab == StudioMainTab.VOICE_ENHANCER,
                                onClick = {
                                    viewModel.pausePlayback()
                                    selectedTab = StudioMainTab.VOICE_ENHANCER
                                },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (isVoiceRunning) Badge()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (selectedTab == StudioMainTab.VOICE_ENHANCER) {
                                                Icons.Rounded.AutoAwesome
                                            } else {
                                                Icons.Outlined.AutoAwesome
                                            },
                                            contentDescription = strings.navVoiceEnhancer
                                        )
                                    }
                                },
                                label = { Text(strings.navVoiceEnhancer) },
                                modifier = Modifier.testTag("nav_voice_enhancer")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            NavigationRailItem(
                                selected = selectedTab == StudioMainTab.VOCAL_REMOVER,
                                onClick = {
                                    voiceEnhancerViewModel.pausePlayback()
                                    selectedTab = StudioMainTab.VOCAL_REMOVER
                                },
                                icon = {
                                    BadgedBox(badge = { if (isVocalRunning) Badge() }) {
                                        Icon(
                                            imageVector = if (selectedTab == StudioMainTab.VOCAL_REMOVER) {
                                                Icons.Rounded.GraphicEq
                                            } else {
                                                Icons.Outlined.GraphicEq
                                            },
                                            contentDescription = strings.navVocalRemover
                                        )
                                    }
                                },
                                label = { Text(strings.navVocalRemover) },
                                modifier = Modifier.testTag("rail_vocal_remover")
                            )
                            NavigationRailItem(
                                selected = selectedTab == StudioMainTab.VOICE_ENHANCER,
                                onClick = {
                                    viewModel.pausePlayback()
                                    selectedTab = StudioMainTab.VOICE_ENHANCER
                                },
                                icon = {
                                    BadgedBox(badge = { if (isVoiceRunning) Badge() }) {
                                        Icon(
                                            imageVector = if (selectedTab == StudioMainTab.VOICE_ENHANCER) {
                                                Icons.Rounded.AutoAwesome
                                            } else {
                                                Icons.Outlined.AutoAwesome
                                            },
                                            contentDescription = strings.navVoiceEnhancer
                                        )
                                    }
                                },
                                label = { Text(strings.navVoiceEnhancer) },
                                modifier = Modifier.testTag("rail_voice_enhancer")
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (selectedTab) {
                            StudioMainTab.VOCAL_REMOVER -> {
                                NavHost(
                                    navController = navController,
                                    startDestination = ROUTE_HOME
                                ) {
                                    composable(ROUTE_HOME) {
                                        HomeScreen(
                                            strings = strings,
                                            preferences = prefs,
                                            recentTracks = recentTracks,
                                            onUploadAudio = { uri -> viewModel.startSeparation(uri) },
                                            onTryDemo = { viewModel.startDemoTrack() },
                                            onOpenTrack = { trackId ->
                                                navController.navigate("results/$trackId") {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onDeleteTrack = { trackId -> viewModel.deleteTrack(trackId) },
                                            onToggleLanguage = { viewModel.toggleQuickLanguage() },
                                            onToggleTheme = { viewModel.toggleQuickTheme() },
                                            onOpenSettings = {
                                                viewModel.refreshStorageUsage()
                                                showSettingsSheet = true
                                            }
                                        )
                                    }

                                    composable(ROUTE_PROCESSING) {
                                        ProcessingScreen(
                                            state = processingState,
                                            strings = strings,
                                            onCancel = { viewModel.cancelProcessing() },
                                            onRetry = { viewModel.retryLastJob() },
                                            onBackHome = {
                                                viewModel.resetProcessingState()
                                                navController.popBackStack(ROUTE_HOME, inclusive = false)
                                            }
                                        )
                                    }

                                    composable(
                                        route = ROUTE_RESULTS,
                                        arguments = listOf(navArgument("trackId") { type = NavType.StringType })
                                    ) { backStackEntry ->
                                        val trackId = backStackEntry.arguments?.getString("trackId") ?: ""
                                        val track by viewModel.observeTrack(trackId)
                                            .collectAsStateWithLifecycle(initialValue = null)

                                        val loadedTrack = track
                                        if (loadedTrack != null) {
                                            ResultsScreen(
                                                track = loadedTrack,
                                                strings = strings,
                                                playbackState = playbackState,
                                                exportState = exportState,
                                                onLoadStems = { viewModel.loadTrackForPlayback(it) },
                                                onToggleVocals = { viewModel.toggleVocals() },
                                                onToggleInstrumental = { viewModel.toggleInstrumental() },
                                                onToggleDualMix = { viewModel.toggleDualMix() },
                                                onMixBalanceChange = { viewModel.setMixBalance(it) },
                                                onSeek = { viewModel.seekToFraction(it) },
                                                onPausePlayback = { viewModel.pausePlayback() },
                                                onExportToMusic = { t, isVocals, fmt ->
                                                    viewModel.exportStemToMusic(
                                                        t,
                                                        isVocals,
                                                        fmt,
                                                        strings.exportSuccessMsg
                                                    )
                                                },
                                                onExportToUri = { t, isVocals, fmt, uri ->
                                                    viewModel.exportStemToUri(
                                                        t,
                                                        isVocals,
                                                        fmt,
                                                        uri,
                                                        strings.exportSuccessMsg
                                                    )
                                                },
                                                onBuildShareIntent = { t, isVocals, fmt ->
                                                    viewModel.buildShareIntent(t, isVocals, fmt)
                                                },
                                                onBackHome = {
                                                    navController.popBackStack(ROUTE_HOME, inclusive = false)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            StudioMainTab.VOICE_ENHANCER -> {
                                VoiceEnhancerScreen(
                                    strings = strings,
                                    preferences = prefs,
                                    stagedInput = veStagedInput,
                                    selectedStrength = veStrength,
                                    processingState = veProcessingState,
                                    activeResult = veActiveResult,
                                    history = veHistory,
                                    playbackState = vePlaybackState,
                                    exportState = veExportState,
                                    onStageMediaUri = { voiceEnhancerViewModel.stageMediaUri(it) },
                                    onTryNoisyDemo = { voiceEnhancerViewModel.stageNoisyDemo() },
                                    onSelectStrength = { voiceEnhancerViewModel.selectStrength(it) },
                                    onStartEnhancement = { voiceEnhancerViewModel.startEnhancement() },
                                    onCancelEnhancement = { voiceEnhancerViewModel.cancelEnhancement() },
                                    onRetryEnhancement = { voiceEnhancerViewModel.retryEnhancement() },
                                    onClearStagedOrResult = { voiceEnhancerViewModel.clearStagedAndResult() },
                                    onOpenResult = { voiceEnhancerViewModel.openResult(it) },
                                    onDeleteResult = { voiceEnhancerViewModel.deleteVoice(it) },
                                    onLoadComparison = { voiceEnhancerViewModel.loadForComparison(it) },
                                    onToggleOriginal = { voiceEnhancerViewModel.toggleOriginal() },
                                    onToggleEnhanced = { voiceEnhancerViewModel.toggleEnhanced() },
                                    onSwitchBeforeAfter = { voiceEnhancerViewModel.switchBeforeAfter(it) },
                                    onSeek = { voiceEnhancerViewModel.seekToFraction(it) },
                                    onPausePlayback = { voiceEnhancerViewModel.pausePlayback() },
                                    onExportToMusic = { entity, fmt ->
                                        voiceEnhancerViewModel.exportEnhancedToMusic(
                                            entity,
                                            fmt,
                                            strings.exportSuccessMsg
                                        )
                                    },
                                    onExportToUri = { entity, fmt, uri ->
                                        voiceEnhancerViewModel.exportEnhancedToUri(
                                            entity,
                                            fmt,
                                            uri,
                                            strings.exportSuccessMsg
                                        )
                                    },
                                    onBuildShareIntent = { entity, fmt ->
                                        voiceEnhancerViewModel.buildShareIntent(entity, fmt)
                                    },
                                    onToggleLanguage = { viewModel.toggleQuickLanguage() },
                                    onToggleTheme = { viewModel.toggleQuickTheme() },
                                    onOpenSettings = {
                                        viewModel.refreshStorageUsage()
                                        showSettingsSheet = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (showSettingsSheet) {
                SettingsBottomSheet(
                    strings = strings,
                    preferences = prefs,
                    storageBytes = storageBytes,
                    serverTestResult = serverTestResult,
                    onDismiss = { showSettingsSheet = false },
                    onSelectLanguage = { viewModel.setLanguage(it) },
                    onSelectTheme = { viewModel.setThemeMode(it) },
                    onSelectEngine = { viewModel.setEngineMode(it) },
                    onUpdateServerUrl = { viewModel.setRemoteServerUrl(it) },
                    onTestConnection = { viewModel.testRemoteServerConnection(it) },
                    onUpdateStrength = { viewModel.setIsolationStrength(it) },
                    onCleanCache = { viewModel.cleanTempCache(strings.cacheClearedMsg) }
                )
            }
        }
    }
}
