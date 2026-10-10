package com.example

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.NetworkDetailScreen
import com.example.ui.screens.NetworksScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.Screen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as WifiTrackerApplication

        setContent {
            MyApplicationTheme {
                // تفعيل اتجاه الواجهة العربية من اليمين إلى اليسار (RTL) بشكل إلزامي
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val viewModel: MainViewModel = viewModel(
                        factory = MainViewModel.Factory(
                            repository = app.repository,
                            networkDetector = app.networkDetector,
                            context = app
                        )
                    )

                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val snackbarHostState = remember { SnackbarHostState() }

                    // مُطلق طلب الصلاحيات (الموقع الدقيق + الإشعارات لأندرويد 13+)
                    val permissionsLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        viewModel.checkWifiStatus()
                        viewModel.recordCurrentSnapshot(isAutomatic = true)
                    }

                    // عرض رسائل التنبيه للمستخدم
                    LaunchedEffect(uiState.feedbackMessage) {
                        uiState.feedbackMessage?.let { msg ->
                            snackbarHostState.showSnackbar(msg)
                            viewModel.clearFeedbackMessage()
                        }
                    }

                    MainAppScaffold(
                        uiState = uiState,
                        snackbarHostState = snackbarHostState,
                        onNavigate = { screen -> viewModel.navigateTo(screen) },
                        onRefreshSnapshot = { viewModel.recordCurrentSnapshot(isAutomatic = false) },
                        onSelectNetwork = { ssid -> viewModel.selectNetwork(ssid); viewModel.navigateTo(Screen.NetworkDetail(ssid)) },
                        onPeriodSelected = { period -> viewModel.setFilterPeriod(period) },
                        onRequestLocationPermission = {
                            val permsToRequest = mutableListOf<String>()
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                permsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                permsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            permissionsLauncher.launch(permsToRequest.toTypedArray())
                        },
                        
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    uiState: com.example.ui.viewmodel.MainUiState,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Screen) -> Unit,
    onRefreshSnapshot: () -> Unit,
    onSelectNetwork: (String) -> Unit,
    onPeriodSelected: (com.example.data.model.FilterPeriod) -> Unit,
    onRequestLocationPermission: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "مراقب استهلاك Wi-Fi",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        bottomBar = {
            // شريط التنقل السفلي يظهر في الشاشات الرئيسية
            if (uiState.currentScreen !is Screen.NetworkDetail) {
                NavigationBar(modifier = Modifier.testTag("bottom_nav_bar")) {
                    NavigationBarItem(
                        selected = uiState.currentScreen is Screen.Home,
                        onClick = { onNavigate(Screen.Home) },
                        icon = { Icon(Icons.Default.Home, contentDescription = "الرئيسية") },
                        label = { Text("الرئيسية") },
                        modifier = Modifier.testTag("nav_item_home")
                    )
                    NavigationBarItem(
                        selected = uiState.currentScreen is Screen.Networks,
                        onClick = { onNavigate(Screen.Networks) },
                        icon = { Icon(Icons.Default.Wifi, contentDescription = "الشبكات") },
                        label = { Text("الشبكات") },
                        modifier = Modifier.testTag("nav_item_networks")
                    )
                    NavigationBarItem(
                        selected = uiState.currentScreen is Screen.History,
                        onClick = { onNavigate(Screen.History) },
                        icon = { Icon(Icons.Default.History, contentDescription = "السجل") },
                        label = { Text("السجل") },
                        modifier = Modifier.testTag("nav_item_history")
                    )
                    NavigationBarItem(
                        selected = uiState.currentScreen is Screen.Settings,
                        onClick = { onNavigate(Screen.Settings) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "الإعدادات") },
                        label = { Text("الإعدادات") },
                        modifier = Modifier.testTag("nav_item_settings")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val screen = uiState.currentScreen) {
                is Screen.Home -> {
                    HomeScreen(
                        uiState = uiState,
                        onNavigate = onNavigate,
                        onRefreshSnapshot = onRefreshSnapshot,
                        onRequestPermissions = onRequestLocationPermission
                    )
                }
                is Screen.Networks -> {
                    NetworksScreen(
                        uiState = uiState,
                        onSelectNetwork = onSelectNetwork
                    )
                }
                is Screen.NetworkDetail -> {
                    NetworkDetailScreen(
                        uiState = uiState,
                        onBack = { onNavigate(Screen.Networks) },
                        onPeriodSelected = onPeriodSelected
                    )
                }
                is Screen.History -> {
                    HistoryScreen(
                        uiState = uiState,
                        onRefreshSnapshot = onRefreshSnapshot
                    )
                }
                is Screen.Settings -> {
                    SettingsScreen(
                        uiState = uiState,
                        onRequestLocationPermission = onRequestLocationPermission
                    )
                }
            }
        }
    }
}
