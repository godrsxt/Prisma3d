package com.prisma3d.scene

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.dragAndDropSource
import androidx.compose.foundation.gestures.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.detectDragGestures
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.OnGloballyPositionedModifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModel
import kotlinx.coroutines.launch
import java.util.UUID

// ============================================================
// Data Models
// ============================================================

enum class EntityType(val icon: ImageVector, val label: String) {
    MESH(Icons.Default.Layers, "Mesh"),
    LIGHT(Icons.Default.Lightbulb, "Light"),
    CAMERA(Icons.Default.Videocam, "Camera"),
    EMPTY(Icons.Default.CropFree, "Empty"),
    BONE(Icons.Default.Straighten, "Bone")
}

data class SceneEntity(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: EntityType,
    var parentId: String? = null,
    var children: MutableList<SceneEntity> = mutableStateListOf(),
    var isExpanded: Boolean = true,
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var order: Int = 0
) {
    fun addChild(child: SceneEntity) {
        child.parentId = this.id
        child.order = children.size
        children.add(child)
    }

    fun removeChild(childId: String): Boolean {
        val index = children.indexOfFirst { it.id == childId }
        if (index >= 0) {
            children.removeAt(index)
            // Reorder
            children.forEachIndexed { idx, c -> c.order = idx }
            return true
        }
        return children.any { it.removeChild(childId) }
    }

    fun findEntity(id: String): SceneEntity? {
        if (this.id == id) return this
        return children.mapNotNull { it.findEntity(id) }.firstOrNull()
    }

    fun flatten(expandedOnly: Boolean = true): List<SceneEntity> {
        val list = mutableListOf<SceneEntity>()
        fun traverse(entity: SceneEntity, depth: Int) {
            entity.order = list.size // Temporary flatten index
            list.add(entity)
            if (entity.isExpanded || !expandedOnly) {
                entity.children.forEach { traverse(it, depth + 1) }
            }
        }
        traverse(this, 0)
        return list
    }
}

data class OutlinerItem(
    val entity: SceneEntity,
    val depth: Int,
    val isLastSibling: Boolean
)

// ============================================================
// ViewModel
// ============================================================

class OutlinerViewModel : ViewModel() {
    // Root entity representing the scene root
    val rootEntity = SceneEntity(id = "root", name = "Scene", type = EntityType.EMPTY).apply {
        isExpanded = true
    }

    var searchQuery by mutableStateOf("")
    var selectedIds by mutableStateOf(mutableSetOf<String>())
    var contextMenuTarget by mutableStateOf<SceneEntity?>(null)
    var contextMenuPosition by mutableStateOf<IntOffset?>(null)
    var dragSourceEntity by mutableStateOf<SceneEntity?>(null)
    var dragTargetEntity by mutableStateOf<SceneEntity?>(null)
    var dropPosition by mutableStateOf<DropPosition>(DropPosition.NONE)

    enum class DropPosition { NONE, ABOVE, BELOW, INSIDE }

    fun toggleExpand(entity: SceneEntity) {
        entity.isExpanded = !entity.isExpanded
    }

    fun selectEntity(entity: SceneEntity, multiSelect: Boolean = false) {
        if (multiSelect) {
            if (selectedIds.contains(entity.id)) selectedIds.remove(entity.id) else selectedIds.add(entity.id)
        } else {
            selectedIds.clear()
            selectedIds.add(entity.id)
        }
    }

    fun selectChildren(entity: SceneEntity) {
        fun collectIds(e: SceneEntity) {
            selectedIds.add(e.id)
            e.children.forEach { collectIds(it) }
        }
        collectIds(entity)
    }

    fun duplicateEntity(entity: SceneEntity) {
        // Deep copy logic simplified
        val newEntity = copyEntity(entity, entity.parentId)
        entity.parentId?.let { parentId ->
            rootEntity.findEntity(parentId)?.addChild(newEntity)
        }
    }

    private fun copyEntity(source: SceneEntity, newParentId: String?): SceneEntity {
        val copy = SceneEntity(
            name = "${source.name} (Copy)",
            type = source.type,
            parentId = newParentId,
            isVisible = source.isVisible,
            isLocked = source.isLocked
        )
        source.children.forEach { child ->
            copy.addChild(copyEntity(child, copy.id))
        }
        return copy
    }

    fun deleteEntity(entity: SceneEntity) {
        entity.parentId?.let { parentId ->
            rootEntity.findEntity(parentId)?.removeChild(entity.id)
        }
        selectedIds.remove(entity.id)
    }

    fun renameEntity(entity: SceneEntity, newName: String) {
        entity.name = newName
    }

    fun toggleVisibility(entity: SceneEntity) {
        entity.isVisible = !entity.isVisible
    }

    fun toggleLock(entity: SceneEntity) {
        entity.isLocked = !entity.isLocked
    }

    fun onDragStart(entity: SceneEntity) {
        dragSourceEntity = entity
    }

    fun onDragEnter(target: SceneEntity, position: DropPosition) {
        if (dragSourceEntity != null && dragSourceEntity != target && !isDescendantOf(target, dragSourceEntity!!)) {
            dragTargetEntity = target
            dropPosition = position
        } else {
            dragTargetEntity = null
            dropPosition = DropPosition.NONE
        }
    }

    fun onDragLeave() {
        dragTargetEntity = null
        dropPosition = DropPosition.NONE
    }

    fun onDrop() {
        val source = dragSourceEntity
        val target = dragTargetEntity
        val pos = dropPosition

        if (source != null && target != null && pos != DropPosition.NONE) {
            // Remove from old parent
            source.parentId?.let { oldParentId ->
                rootEntity.findEntity(oldParentId)?.removeChild(source.id)
            }

            // Add to new parent / position
            when (pos) {
                DropPosition.INSIDE -> {
                    target.addChild(source)
                }
                DropPosition.ABOVE, DropPosition.BELOW -> {
                    target.parentId?.let { parentId ->
                        val parent = rootEntity.findEntity(parentId)
                        parent?.children?.add(target.order + (if (pos == DropPosition.BELOW) 1 else 0), source)
                        source.parentId = parentId
                        parent?.children?.forEachIndexed { idx, c -> c.order = idx }
                    }
                }
            }
        }
        dragSourceEntity = null
        dragTargetEntity = null
        dropPosition = DropPosition.NONE
    }

    private fun isDescendantOf(potentialDescendant: SceneEntity, ancestor: SceneEntity): Boolean {
        var current: SceneEntity? = potentialDescendant
        while (current != null) {
            if (current.id == ancestor.id) return true
            current = current.parentId?.let { rootEntity.findEntity(it) }
        }
        return false
    }

    fun getFilteredItems(): List<OutlinerItem> {
        val flatList = rootEntity.flatten()
        val filtered = if (searchQuery.isBlank()) {
            flatList
        } else {
            flatList.filter { it.name.lowercase().contains(searchQuery.lowercase()) }
        }
        return buildOutlinerItems(filtered)
    }

    private fun buildOutlinerItems(entities: List<SceneEntity>): List<OutlinerItem> {
        val items = mutableListOf<OutlinerItem>()
        // This is a simplified flattening for UI. A real implementation needs to maintain hierarchy depth visually.
        // We traverse the root to get depth correctly.
        fun traverse(entity: SceneEntity, depth: Int) {
            if (entities.contains(entity)) {
                val siblings = entity.parentId?.let { rootEntity.findEntity(it)?.children } ?: rootEntity.children
                val isLast = siblings?.lastOrNull()?.id == entity.id
                items.add(OutlinerItem(entity, depth, isLast))
            }
            if (entity.isExpanded) {
                entity.children.forEach { traverse(it, depth + 1) }
            }
        }
        rootEntity.children.forEach { traverse(it, 0) }
        return items
    }
}

// ============================================================
// Icons (Placeholder - Replace with actual Icons/Icons.Outlined imports)
// ============================================================

object Icons {
    object Default {
        val Layers = androidx.compose.material.icons.Icons.Default.Layers
        val Lightbulb = androidx.compose.material.icons.Icons.Default.Lightbulb
        val Videocam = androidx.compose.material.icons.Icons.Default.Videocam
        val CropFree = androidx.compose.material.icons.Icons.Default.CropFree
        val Straighten = androidx.compose.material.icons.Icons.Default.Straighten
        val ExpandMore = androidx.compose.material.icons.Icons.Default.ExpandMore
        val ChevronRight = androidx.compose.material.icons.Icons.Default.ChevronRight
        val Visibility = androidx.compose.material.icons.Icons.Default.Visibility
        val VisibilityOff = androidx.compose.material.icons.Icons.Default.VisibilityOff
        val Lock = androidx.compose.material.icons.Icons.Default.Lock
        val LockOpen = androidx.compose.material.icons.Icons.Default.LockOpen
        val ContentCopy = androidx.compose.material.icons.Icons.Default.ContentCopy
        val Delete = androidx.compose.material.icons.Icons.Default.Delete
        val DriveFileRenameOutline = androidx.compose.material.icons.Icons.Default.DriveFileRenameOutline
        val Checklist = androidx.compose.material.icons.Icons.Default.Checklist
        val Search = androidx.compose.material.icons.Icons.Default.Search
        val Close = androidx.compose.material.icons.Icons.Default.Close
    }
}

// ============================================================
// Composable Screen
// ============================================================

@Composable
fun OutlinerScreen(
    viewModel: OutlinerViewModel = viewModel(),
    onSelectionChange: (Set<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinerToolbar(viewModel = viewModel)
        OutlinerList(viewModel = viewModel, onSelectionChange = onSelectionChange)
    }
}

@Composable
fun OutlinerToolbar(viewModel: OutlinerViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var query by remember { mutableStateOf(viewModel.searchQuery) }
        TextField(
            value = query,
            onValueChange = { viewModel.searchQuery = it; query = it },
            placeholder = { Text("Search hierarchy...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { viewModel.searchQuery = ""; query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
fun OutlinerList(
    viewModel: OutlinerViewModel,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = remember(viewModel.searchQuery, viewModel.rootEntity) {
        viewModel.getFilteredItems()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(items, key = { it.entity.id }) { item ->
            OutlinerRow(
                item = item,
                viewModel = viewModel,
                onSelectionChange = onSelectionChange
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OutlinerRow(
    item: OutlinerItem,
    viewModel: OutlinerViewModel,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val entity = item.entity
    val isSelected = viewModel.selectedIds.contains(entity.id)
    val isDragging = viewModel.dragSourceEntity?.id == entity.id
    val isDropTarget = viewModel.dragTargetEntity?.id == entity.id
    val dropPos = viewModel.dropPosition

    val indentation = item.depth * 20.dp
    val hasChildren = entity.children.isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indentation)
            .background(
                Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .pointerInput(entity) {
                // Context Menu / Right Click / Long Press
                awaitFirstDown(requireUnconsumed = false)
                detectDragGestures(
                    onDragStart = { viewModel.onDragStart(entity) },
                    onDragEnd = { viewModel.onDrop() }
                )
            }
            .dragAndDropSource(
                data = { entity.id },
                dragThreshold = 8.dp,
                onDragStart = { viewModel.onDragStart(entity) },
                onDragStop = { viewModel.onDrop() }
            )
            .dragAndDropTarget(
                delegate = object : DragAndDropTarget {
                    override fun onDragEnter(event: DragEnterEvent) {
                        // Calculate drop position based on Y offset relative to item height
                        // Simplified: Assume INSIDE for now, real impl needs coordinates
                        viewModel.onDragEnter(entity, DropPosition.INSIDE)
                    }
                    override fun onDragLeave(event: DragLeaveEvent) {
                        viewModel.onDragLeave()
                    }
                    override fun onDrop(event: DropEvent): Boolean {
                        viewModel.onDrop()
                        return true
                    }
                }
            )
            .onGloballyPositioned { coords ->
                // Could store coords for precise drop position calculation (Above/Below/Inside)
            }
            .selectable(
                selected = isSelected,
                onClick = { viewModel.selectEntity(entity, false); onSelectionChange(viewModel.selectedIds) },
                role = androidx.compose.ui.semantics.Role.ListItem
            )
            .contextMenu {
                viewModel.contextMenuTarget = entity
                viewModel.contextMenuPosition = IntOffset(coords.positionInRoot().x.roundToInt(), coords.positionInRoot().y.roundToInt())
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else if (isDropTarget) {
                        when (dropPos) {
                            DropPosition.INSIDE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            DropPosition.ABOVE, DropPosition.BELOW -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            else -> Color.Transparent
                        }
                    } else Color.Transparent,
                    RoundedCornerShape(4.dp)
                )
                .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        ) {
            // Expand/Collapse Arrow
            if (hasChildren) {
                Icon(
                    imageVector = if (entity.isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (entity.isExpanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(24.dp)
                        .fillMaxHeight()
                        .clickable { viewModel.toggleExpand(entity) }
                        .padding(end = 4.dp)
                )
            } else {
                Box(modifier = Modifier.size(24.dp)) // Placeholder for alignment
            }

            // Visibility Icon
            IconButton(onClick = { viewModel.toggleVisibility(entity) }) {
                Icon(
                    imageVector = if (entity.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (entity.isVisible) "Hide" : "Show",
                    tint = if (entity.isVisible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            // Lock Icon
            IconButton(onClick = { viewModel.toggleLock(entity) }) {
                Icon(
                    imageVector = if (entity.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (entity.isLocked) "Unlock" : "Lock",
                    tint = if (entity.isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            // Entity Type Icon
            EntityIcon(type = entity.type, modifier = Modifier.size(20.dp).padding(end = 8.dp))

            // Name
            Text(
                text = entity.name,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start).padding(top = 2.dp)
            )
        }

        // Drop Indicators (Above/Below lines) - drawn via background or separate Box
        if (isDropTarget && dropPos != DropPosition.INSIDE) {
            // This is a visual hack; proper implementation uses custom Layout or Drawing
        }
    }
}

@Composable
fun EntityIcon(type: EntityType, modifier: Modifier = Modifier) {
    Icon(
        imageVector = type.icon,
        contentDescription = type.label,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

// ============================================================
// Context Menu Implementation (Simplified)
// ============================================================

@Composable
fun Modifier.contextMenu(onLongPress: (LayoutCoordinates) -> Unit): Modifier = composed {
    this.pointerInput(Unit) {
        awaitFirstDown(requireUnconsumed = false)
        detectDragGestures(
            onLongPress = { offset ->
                // Compose doesn't have easy long press in pointerInput without detectTapGestures
            }
        )
    }
    // Using combinedClickable for long press + click
}.combinedClickable(
    onClick = { /* Selection handled by selectable */ },
    onLongClick = { coords -> onLongPress(coords) },
    onDoubleClick = null
)

// Simple Dropdown Menu for Context Actions
@Composable
fun OutlinerContextMenu(
    viewModel: OutlinerViewModel,
    onDismiss: () -> Unit
) {
    val target = viewModel.contextMenuTarget
    if (target == null) return

    // In a real app, use DropdownMenu anchored to a Box or use Popup
    // This is a placeholder representation
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
    ) {
        // TODO: Implement actual DropdownMenu positioned at viewModel.contextMenuPosition
        // Menu Items:
        // Duplicate -> viewModel.duplicateEntity(target)
        // Delete -> viewModel.deleteEntity(target)
        // Rename -> Show Dialog
        // Hide/Lock -> Toggle
        // Select Children -> viewModel.selectChildren(target)
    }
}

// ============================================================
// Selection Manager Integration (Interface)
// ============================================================

interface SelectionManager {
    fun setSelection(ids: Set<String>)
    fun addToSelection(ids: Set<String>)
    fun removeFromSelection(ids: Set<String>)
    val currentSelection: Set<String>
}

// Usage in ViewModel or Screen:
// val selectionManager = remember { SelectionManagerImpl() }
// OutlinerScreen(onSelectionChange = { selectionManager.setSelection(it) })

// ============================================================
// Helper Extensions / Data
// ============================================================

data class IntOffset(val x: Int, val y: Int)