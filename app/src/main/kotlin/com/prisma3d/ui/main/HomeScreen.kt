package com.prisma3d.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prisma3d.ui.components.InspectorPane
import com.prisma3d.ui.components.OutlinerPane
import com.prisma3d.ui.components.PrismaViewport
import com.prisma3d.ui.components.TimelinePane
import com.prisma3d.ui.theme.PrismaTheme
import com.prisma3d.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeActivity : ComponentActivity() {
    @Inject
    lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PrismaTheme {
                HomeScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: MainViewModel) {
    var showTimeline by remember { mutableStateOf(true) }
    var showOutliner by remember { mutableStateOf(true) }
    var showInspector by remember { mutableStateOf(true) }
    var expandedMenu by remember { mutableStateOf<ExpandedMenu?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val timelineHeight = animateDpAsState(
        targetValue = if (showTimeline) 200.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumStiff, stiffness = Spring.StiffnessMedium)
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { expandedMenu = ExpandedMenu.AddPrimitive },
                containerColor = PrismaTheme.colorScheme.primaryContainer,
                contentColor = PrismaTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Primitive")
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.End,
        topBar = {
            HomeTopAppBar(
                viewModel = viewModel,
                expandedMenu = expandedMenu,
                onMenuExpanded = { expandedMenu = it },
                onMenuCollapsed = { expandedMenu = null },
                showTimeline = showTimeline,
                onTimelineToggle = { showTimeline = !showTimeline },
                showOutliner = showOutliner,
                onOutlinerToggle = { showOutliner = !showOutliner },
                showInspector = showInspector,
                onInspectorToggle = { showInspector = !showInspector }
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = showTimeline) {
                TimelinePane(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(timelineHeight.value)
                )
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(visible = showOutliner) {
                OutlinerPane(
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(0.2f, fill = false)
                        .fillMaxSize()
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                )
            }

            PrismaViewport(
                viewModel = viewModel,
                modifier = Modifier
                    .weight(0.6f, fill = true)
                    .fillMaxSize()
                    .padding(8.dp)
            )

            AnimatedVisibility(visible = showInspector) {
                InspectorPane(
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(0.2f, fill = false)
                        .fillMaxSize()
                        .padding(end = 8.dp, top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeTopAppBar(
    viewModel: MainViewModel,
    expandedMenu: ExpandedMenu?,
    onMenuExpanded: (ExpandedMenu?) -> Unit,
    onMenuCollapsed: () -> Unit,
    showTimeline: Boolean,
    onTimelineToggle: () -> Unit,
    showOutliner: Boolean,
    onOutlinerToggle: () -> Unit,
    showInspector: Boolean,
    onInspectorToggle: () -> Unit
) {
    TopAppBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = PrismaTheme.colorScheme.surfaceContainerLow,
        title = {
            Text(
                text = "Prisma3D",
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = PrismaTheme.colorScheme.onSurface
            )
        },
        navigationIcon = {
            DropdownMenuAnchor(
                expandedMenu = expandedMenu,
                onExpand = { onMenuExpanded(ExpandedMenu.File) },
                onCollapse = onMenuCollapsed
            ) {
                FileMenuContent(onDismiss = onMenuCollapsed)
            }
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                DropdownMenuAnchor(
                    expandedMenu = expandedMenu,
                    onExpand = { onMenuExpanded(ExpandedMenu.Edit) },
                    onCollapse = onMenuCollapsed
                ) {
                    EditMenuContent(viewModel = viewModel, onDismiss = onMenuCollapsed)
                }

                DropdownMenuAnchor(
                    expandedMenu = expandedMenu,
                    onExpand = { onMenuExpanded(ExpandedMenu.Mode) },
                    onCollapse = onMenuCollapsed
                ) {
                    ModeSelectorContent(viewModel = viewModel, onDismiss = onMenuCollapsed)
                }

                PlaybackControls(viewModel = viewModel)

                IconButton(onClick = { /* Render action */ }) {
                    Icon(Icons.Filled.Image, contentDescription = "Render", tint = PrismaTheme.colorScheme.primary)
                }

                IconButton(onClick = onTimelineToggle) {
                    Icon(
                        if (showTimeline) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = if (showTimeline) "Hide Timeline" else "Show Timeline",
                        tint = PrismaTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onOutlinerToggle) {
                    Icon(
                        Icons.Filled.Layers,
                        contentDescription = if (showOutliner) "Hide Outliner" else "Show Outliner",
                        tint = if (showOutliner) PrismaTheme.colorScheme.primary else PrismaTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onInspectorToggle) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = if (showInspector) "Hide Inspector" else "Show Inspector",
                        tint = if (showInspector) PrismaTheme.colorScheme.primary else PrismaTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = PrismaTheme.colorScheme.surfaceContainerLow,
            titleContentColor = PrismaTheme.colorScheme.onSurface,
            navigationIconContentColor = PrismaTheme.colorScheme.onSurface,
            actionIconContentColor = PrismaTheme.colorScheme.onSurface
        )
    )
}

@Composable
fun DropdownMenuAnchor(
    expandedMenu: ExpandedMenu?,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    content: @Composable () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val currentMenu = expandedMenu

    if (currentMenu != null) {
        showMenu = true
    }

    Box {
        IconButton(onClick = { if (!showMenu) onExpand() else onCollapse() }) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Menu")
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = onCollapse,
            modifier = Modifier.fillMaxWidth()
        ) {
            content()
        }
    }
}

@Composable
fun FileMenuContent(onDismiss: () -> Unit) {
    Column {
        DropdownMenuItem(text = { Text("New Scene") }, onClick = { onDismiss() })
        DropdownMenuItem(text = { Text("Open Scene") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) }, onClick = { onDismiss() })
        DropdownMenuItem(text = { Text("Save") }, leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) }, onClick = { onDismiss() })
        DropdownMenuItem(text = { Text("Save As...") }, onClick = { onDismiss() })
        androidx.compose.material3.Divider()
        DropdownMenuItem(text = { Text("Import") }, leadingIcon = { Icon(Icons.Filled.FileUpload, contentDescription = null) }, onClick = { onDismiss() })
        DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) }, onClick = { onDismiss() })
        androidx.compose.material3.Divider()
        DropdownMenuItem(text = { Text("Preferences") }, leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) }, onClick = { onDismiss() })
        DropdownMenuItem(text = { Text("Quit") }, onClick = { onDismiss() })
    }
}

@Composable
fun EditMenuContent(viewModel: MainViewModel, onDismiss: () -> Unit) {
    Column {
        DropdownMenuItem(
            text = { Text("Undo") },
            leadingIcon = { Icon(Icons.Filled.Undo, contentDescription = null) },
            onClick = { viewModel.undo(); onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Redo") },
            leadingIcon = { Icon(Icons.Filled.ArrowForward, contentDescription = null) },
            onClick = { viewModel.redo(); onDismiss() }
        )
        androidx.compose.material3.Divider()
        DropdownMenuItem(
            text = { Text("Cut") },
            leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
            onClick = { onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Copy") },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            onClick = { onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Paste") },
            leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
            onClick = { onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = { onDismiss() }
        )
        androidx.compose.material3.Divider()
        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { onDismiss() })
        DropdownMenuItem(text = { Text("Select All") }, onClick = { onDismiss() })
        DropdownMenuItem(text = { Text("Deselect All") }, onClick = { onDismiss() })
    }
}

@Composable
fun ModeSelectorContent(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val modes = listOf(
        EditorMode.Object to "Object Mode",
        EditorMode.Edit to "Edit Mode",
        EditorMode.Sculpt to "Sculpt Mode",
        EditorMode.UV to "UV Editing",
        EditorMode.Pose to "Pose Mode"
    )

    Column {
        modes.forEach { (mode, label) ->
            val isSelected = viewModel.currentMode == mode
            DropdownMenuItem(
                text = { Text(label, color = if (isSelected) PrismaTheme.colorScheme.primary else PrismaTheme.colorScheme.onSurface) },
                onClick = { viewModel.setMode(mode); onDismiss() }
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = PrismaTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun PlaybackControls(viewModel: MainViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { viewModel.jumpToStart() }) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Jump to Start")
        }
        IconButton(onClick = { viewModel.previousFrame() }) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Previous Frame")
        }
        IconButton(onClick = { viewModel.togglePlayback() }) {
            Icon(
                if (viewModel.isPlaying) Icons.Filled.ArrowForward else Icons.Filled.PlayArrow,
                contentDescription = if (viewModel.isPlaying) "Pause" else "Play"
            )
        }
        IconButton(onClick = { viewModel.nextFrame() }) {
            Icon(Icons.Filled.ArrowForward, contentDescription = "Next Frame")
        }
        IconButton(onClick = { viewModel.jumpToEnd() }) {
            Icon(Icons.Filled.ArrowForward, contentDescription = "Jump to End")
        }
    }
}

enum class ExpandedMenu {
    File, Edit, Mode, AddPrimitive
}

enum class EditorMode {
    Object, Edit, Sculpt, UV, Pose
}

@Composable
@Preview(showBackground = true)
fun HomeScreenPreview() {
    PrismaTheme {
        HomeScreen(viewModel = MainViewModel())
    }
}