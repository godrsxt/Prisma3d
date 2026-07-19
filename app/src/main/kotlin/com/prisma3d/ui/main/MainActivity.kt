package com.prisma3d.ui.main

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.onBackPressedDispatcher
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DialogProperties
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navArgument
import androidx.navigation.compose.rememberNavController
import androidx.window.embedding.ActivityRule
import androidx.window.embedding.SplitRule
import com.google.accompanist.insets.navigationBarsPadding
import com.google.accompanist.insets.statusBarsPadding
import com.google.accompanist.insets.systemBarsPadding
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.accompanist.systemuicontroller.toWindowInsetsControllerCompat
import com.prisma3d.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var navController: NavController
    private var isImmersiveMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Edge-to-Edge setup
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val systemUiController = rememberSystemUiController()
            val windowInsetsController = systemUiController.toWindowInsetsControllerCompat()
            navController = rememberNavController()

            // Observe navigation destination to toggle immersive mode
            val currentRoute by remember { mutableStateOf<String>("") }
            val backDispatcher = onBackPressedDispatcher

            // Setup Back Handling
            setupBackHandler(backDispatcher, navController, currentRoute)

            ProvideWindowInsets {
                MaterialTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        NavHost(navController, startDestination = "start") {
                            composable("start") {
                                StartScreen(onNavigate = { navController.navigate("splash") })
                            }
                            composable("splash") {
                                SplashScreen(onNavigate = { navController.navigate("home") })
                            }
                            composable("home") {
                                HomeScreen(
                                    onOpenSettings = { navController.navigate("settings") },
                                    onOpenAssetBrowser = { navController.navigate("asset_browser") },
                                    onOpenExport = { navController.navigate("export_dialog") },
                                    systemUiController = systemUiController,
                                    windowInsetsController = windowInsetsController,
                                    onImmersiveChange = { enabled -> isImmersiveMode = enabled }
                                )
                            }
                            composable("settings") {
                                SettingsScreen(onBack = { navController.popBackStack() })
                            }
                            composable("asset_browser") {
                                AssetBrowserScreen(onBack = { navController.popBackStack() })
                            }
                            dialog("export_dialog") {
                                ExportDialogScreen(
                                    onDismiss = { navController.popBackStack() },
                                    onExportStart = { scope, serviceIntent ->
                                        // Manage Foreground Service
                                        startExportForegroundService(scope, serviceIntent)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupBackHandler(
        dispatcher: androidx.activity.OnBackPressedDispatcher,
        navController: NavController,
        currentRoute: String
    ) {
        val callback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val destination = navController.currentDestination?.route
                when (destination) {
                    "home" -> {
                        if (isImmersiveMode) {
                            // Exit immersive mode first? Or show exit dialog.
                            showExitConfirmationDialog()
                        } else {
                            showExitConfirmationDialog()
                        }
                    }
                    "settings", "asset_browser" -> {
                        navController.popBackStack()
                    }
                    "export_dialog" -> {
                        // Handled by Dialog dismiss usually, but ensure pop
                        navController.popBackStack()
                    }
                    else -> {
                        if (navController.currentDestination != null) {
                            navController.popBackStack()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        }
        dispatcher.addCallback(this, callback)

        // Update current route for logic if needed
        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentRoute = destination.route
        }
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit Prisma3D?")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { _, _ -> finish() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun startExportForegroundService(scope: CoroutineScope, intent: Intent) {
        scope.launch {
            // Ensure service starts correctly on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.startForegroundService(this@MainActivity, intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop foreground service if activity destroyed while exporting?
        // Usually service manages its own lifecycle via stopSelf.
    }
}

// --- Screen Composables ---

@Composable
fun StartScreen(onNavigate: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Prisma3D", fontSize = 48.sp)
        // Auto navigate after delay
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(500)
            onNavigate()
        }
    }
}

@Composable
fun SplashScreen(onNavigate: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator()
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.foundation.layout.padding(16.dp))
            Text("Loading Assets...", fontSize = 18.sp)
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            onNavigate()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAssetBrowser: () -> Unit,
    onOpenExport: () -> Unit,
    systemUiController: com.google.accompanist.systemuicontroller.SystemUiController,
    windowInsetsController: androidx.core.view.WindowInsetsControllerCompat,
    onImmersiveChange: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val immersive = remember { mutableStateOf(true) }

    // Apply Immersive Sticky for Viewport
    androidx.compose.runtime.LaunchedEffect(immersive.value) {
        if (immersive.value) {
            systemUiController.setSystemBarsBehavior(com.google.accompanist.systemuicontroller.SystemBarsBehavior.SHOW_TRANSIENT_BARS_BY_SWIPE)
            windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            systemUiController.setSystemBarsBehavior(com.google.accompanist.systemuicontroller.SystemBarsBehavior.SHOW_BARS_BY_SWIPE)
            windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_BARS_BY_SWIPE
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Prisma3D Viewport") },
                actions = {
                    IconButton(onClick = onOpenAssetBrowser) {
                        Icon(painterResource(id = R.drawable.ic_folder_open), contentDescription = "Assets")
                    }
                    IconButton(onClick = onOpenExport) {
                        Icon(painterResource(id = R.drawable.ic_export), contentDescription = "Export")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(id = R.drawable.ic_settings), contentDescription = "Settings")
                    }
                    IconButton(onClick = { immersive.value = !immersive.value; onImmersiveChange(immersive.value) }) {
                        Icon(
                            painterResource(id = if (immersive.value) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen),
                            contentDescription = if (immersive.value) "Exit Immersive" else "Enter Immersive"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding() // Handle insets for Scaffold content
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black) // Viewport Background
        ) {
            // Viewport Content (OpenGL View / Compose Canvas)
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                // Draw 3D Scene Placeholder
                drawRect(Color.DarkGray, style = androidx.compose.ui.graphics.Stroke(width = 4.dp.toPx()))
                drawText("3D Viewport", color = Color.White, fontSize = 48.sp, x = size.width / 2 - 100, y = size.height / 2)
            }

            // UI Overlay (Bottom Bar)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Selected: Cube_01 | Tris: 1.2k", fontSize = 14.sp)
                        Text("FPS: 60 | Mem: 45MB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(id = R.drawable.ic_arrow_back), "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            Text("Settings Screen", style = MaterialTheme.typography.headlineMedium)
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.foundation.layout.padding(16.dp))
            Text("Rendering Quality: High")
            Text("Auto Save: Enabled")
            Text("Theme: System Default")
        }
    }
}

@Composable
fun AssetBrowserScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Asset Browser") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(id = R.drawable.ic_arrow_back), "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        }
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(20) { index ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { /* Select Asset */ }
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer)) {
                            Icon(painterResource(id = R.drawable.ic_image), contentDescription = "Thumb")
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.foundation.layout.padding(16.dp))
                        androidx.compose.foundation.layout.Column {
                            Text("Model_$index.glb", style = MaterialTheme.typography.titleMedium)
                            Text("1.2 MB • 5k Tris", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDialogScreen(
    onDismiss: () -> Unit,
    onExportStart: (CoroutineScope, Intent) -> Unit
) {
    val scope = rememberCoroutineScope()
    val exportProgress by remember { mutableStateOf(0f) }
    val isExporting by remember { mutableStateOf(false) }

    val properties = DialogProperties(dismissOnBackPress = !isExporting, dismissOnClickOutside = !isExporting)

    androidx.compose.material3.dialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        properties = properties
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp).wrapContentHeight(),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Export Project", style = MaterialTheme.typology.headlineSmall)
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.foundation.layout.padding(16.dp))

                if (!isExporting) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Format: GLTF (.glb)")
                        Text("Resolution: 4K Textures")
                        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.foundation.layout.padding(16.dp))
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { onDismiss() }) { Text("Cancel") }
                            Button(
                                onClick = {
                                    isExporting = true
                                    val intent = Intent(LocalContext.current, ExportForegroundService::class.java)
                                        .putExtra("PROGRESS_CHANNEL", "export_progress")
                                    onExportStart(scope, intent)
                                },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) { Text("Start Export") }
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.LinearProgressIndicator(progress = exportProgress)
                        androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.foundation.layout.padding(8.dp))
                        Text("Exporting... ${(exportProgress * 100).toInt()}%")
                        Text("Do not close app. Running in foreground.")
                        // Simulate progress update from service callback (omitted for brevity)
                    }
                }
            }
        }
    }
}

// --- Foreground Service ---

class ExportForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "prisma_export_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.prisma3d.ACTION_STOP_EXPORT"
        const val EXTRA_PROGRESS = "export_progress"
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(0, "Preparing Export...")
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        // Simulate Long Export Task
        lifecycleScope.launch {
            repeat(101) { progress ->
                kotlinx.coroutines.delay(100) // Simulate work
                val updatedNotification = buildNotification(progress / 100f, "Exporting... $progress%")
                notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                // Broadcast progress to UI if needed
                sendBroadcast(Intent("EXPORT_PROGRESS").putExtra(EXTRA_PROGRESS, progress / 100f))
            }
            // Finish
            stopSelf()
            sendBroadcast(Intent("EXPORT_COMPLETE"))
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Export Operations",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of 3D asset exports"
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Float, text: String): Notification {
        val stopIntent = Intent(this, ExportForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )

        val contentIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Prisma3D Export")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_export) // Requires drawable
            .setContentIntent(contentPendingIntent)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(R.drawable.ic_close, "Cancel", stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        notificationManager.cancel(NOTIFICATION_ID)
    }
}