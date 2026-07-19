package com.prisma3d.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.spacing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.icons.Icons
import androidx.compose.material3.icons.filled.ArrowDropDown
import androidx.compose.material3.icons.filled.ArrowRight
import androidx.compose.material3.icons.filled.ExpandMore
import androidx.compose.material3.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialDescriptor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.UUID

// ============================================================
// Core Interfaces & Data Structures
// ============================================================

/**
 * Represents a single editable property extracted from an object.
 */
data class PropertyDescriptor(
    val name: String,
    val displayName: String,
    val type: PropertyType,
    val getter: (Any) -> Any?,
    val setter: (Any, Any) -> Unit,
    val isReadOnly: Boolean = false,
    val metadata: Map<String, Any> = emptyMap(),
    val children: List<PropertyDescriptor> = emptyList() // For nested objects
)

/**
 * Enum defining the supported property types for editor mapping.
 */
enum class PropertyType(
    val defaultEditor: String
) {
    INT("DragNumber"),
    FLOAT("DragNumber"),
    DOUBLE("DragNumber"),
    BOOLEAN("Switch"),
    STRING("TextField"),
    ENUM("Dropdown"),
    COLOR("ColorPicker"),
    VECTOR2("VectorInput"),
    VECTOR3("VectorInput"),
    VECTOR4("VectorInput"),
    RESOURCE("AssetBrowser"),
    CURVE("CurveEditor"),
    NESTED_OBJECT("CollapsibleContainer"),
    UNKNOWN("TextField")
}

/**
 * Command Pattern interface for Undo/Redo support.
 */
interface PropertyCommand {
    val description: String
    fun execute()
    fun undo()
}

/**
 * Simple command implementation for setting a property value.
 */
class SetPropertyCommand(
    private val target: Any,
    private val descriptor: PropertyDescriptor,
    private val newValue: Any,
    private val oldValue: Any,
    override val description: String = "Set ${descriptor.displayName}"
) : PropertyCommand {
    override fun execute() {
        descriptor.setter(target, newValue)
    }

    override fun undo() {
        descriptor.setter(target, oldValue)
    }
}

/**
 * Interface for the Undo/Redo stack manager.
 */
interface CommandManager {
    fun executeCommand(command: PropertyCommand)
    fun undo()
    fun redo()
    val canUndo: Boolean
    val canRedo: Boolean
}

/**
 * Registry mapping PropertyTypes or KClasses to Editor Composables.
 */
interface PropertyEditorRegistry {
    @Composable
    fun editProperty(
        descriptor: PropertyDescriptor,
        target: Any,
        commandManager: CommandManager,
        modifier: Modifier = Modifier,
        depth: Int = 0
    )
}

// ============================================================
// Default Implementation: Reflection/Serialization Extractor
// ============================================================

object PropertyExtractor {

    private const val MAX_DEPTH = 5

    fun extractProperties(
        obj: Any,
        serializersModule: SerializersModule = SerializersModule(),
        depth: Int = 0
    ): List<PropertyDescriptor> {
        if (depth > MAX_DEPTH) return emptyList()

        return if (isSerializable(obj)) {
            extractFromSerialization(obj, serializersModule, depth)
        } else {
            extractFromReflection(obj, depth)
        }
    }

    private fun isSerializable(obj: Any): Boolean {
        return obj::class.isAnnotationPresent(kotlinx.serialization.Serializable::class.java)
    }

    private fun extractFromSerialization(
        obj: Any,
        serializersModule: SerializersModule,
        depth: Int
    ): List<PropertyDescriptor> {
        val descriptor = serializersModule.getSerializer(obj::class).descriptor
        val json = Json { ignoreUnknownKeys = true }
        val jsonElement = json.encodeToJsonElement(serializersModule.getSerializer(obj::class), obj)
        val jsonObject = jsonElement.jsonObject

        return descriptor.elementsIndices.mapNotNull { index ->
            val elementDescriptor = descriptor.getElementDescriptor(index)
            val name = descriptor.getElementName(index)
            val value = jsonObject[name]?.let { parseJsonElement(it, elementDescriptor, serializersModule) }

            createDescriptor(
                name = name,
                displayName = formatDisplayName(name),
                serialDescriptor = elementDescriptor,
                value = value,
                target = obj,
                depth = depth,
                serializersModule = serializersModule
            )
        }
    }

    private fun extractFromReflection(obj: Any, depth: Int): List<PropertyDescriptor> {
        val fields = obj::class.members.filterIsInstance<Field>().filter { !it.isStatic && !it.isSynthetic }
        return fields.mapNotNull { field ->
            field.isAccessible = true
            val value = field.get(obj)
            createDescriptor(
                name = field.name,
                displayName = formatDisplayName(field.name),
                kClass = field.type,
                value = value,
                target = obj,
                field = field,
                depth = depth
            )
        }
    }

    private fun createDescriptor(
        name: String,
        displayName: String,
        serialDescriptor: SerialDescriptor? = null,
        kClass: KClass<*>? = null,
        value: Any?,
        target: Any,
        field: Field? = null,
        depth: Int,
        serializersModule: SerializersModule = SerializersModule()
    ): PropertyDescriptor? {
        val type = resolvePropertyType(serialDescriptor, kClass, value)
        val isReadOnly = field?.let { Modifier.isFinal(it.modifiers) } ?: false

        val getter: (Any) -> Any? = if (field != null) { it -> field.get(it) } else { _ -> value }
        val setter: (Any, Any) -> Unit = if (field != null) { t, v -> field.set(t, v) } else { _, _ -> }

        val children = if (type == PropertyType.NESTED_OBJECT && value != null) {
            extractProperties(value, serializersModule, depth + 1)
        } else emptyList()

        return PropertyDescriptor(
            name = name,
            displayName = displayName,
            type = type,
            getter = getter,
            setter = setter,
            isReadOnly = isReadOnly,
            children = children
        )
    }

    private fun resolvePropertyType(
        serialDescriptor: SerialDescriptor?,
        kClass: KClass<*>?,
        value: Any?
    ): PropertyType {
        // 1. Check Serialization Descriptor Kind
        serialDescriptor?.let {
            when (it.kind) {
                StructureKind.ENUM -> return PropertyType.ENUM
                StructureKind.CLASS -> {
                    val serialName = it.serialName.lowercase()
                    return when (serialName) {
                        "color", "rgba", "rgb" -> PropertyType.COLOR
                        "vector2", "vec2" -> PropertyType.VECTOR2
                        "vector3", "vec3" -> PropertyType.VECTOR3
                        "vector4", "vec4" -> PropertyType.VECTOR4
                        "curve", "animationcurve" -> PropertyType.CURVE
                        "resource", "assetref", "assetreference" -> PropertyType.RESOURCE
                        else -> PropertyType.NESTED_OBJECT
                    }
                }
                StructureKind.LIST, StructureKind.MAP -> return PropertyType.UNKNOWN // TODO: List Editor
                else -> {
                    if (it.kind.isPrimitive) {
                        return when (it.kind) {
                            PrimitiveKind.BOOLEAN -> PropertyType.BOOLEAN
                            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> PropertyType.INT
                            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> PropertyType.FLOAT
                            PrimitiveKind.STRING -> PropertyType.STRING
                            else -> PropertyType.UNKNOWN
                        }
                    }
                }
            }
        }

        // 2. Fallback to KClass / Runtime Value
        val clazz = kClass ?? value?.javaClass?.kotlin
        return clazz?.let { resolveByKClass(it, value) } ?: PropertyType.UNKNOWN
    }

    private fun resolveByKClass(kClass: KClass<*>, value: Any?): PropertyType {
        val qualifiedName = kClass.qualifiedName?.lowercase() ?: ""
        val simpleName = kClass.simpleName?.lowercase() ?: ""

        return when {
            kClass.isSubclassOf(Enum::class) -> PropertyType.ENUM
            qualifiedName.contains("color") || simpleName == "color" -> PropertyType.COLOR
            qualifiedName.contains("vector3") || simpleName == "vector3" -> PropertyType.VECTOR3
            qualifiedName.contains("vector2") || simpleName == "vector2" -> PropertyType.VECTOR2
            qualifiedName.contains("vector4") || simpleName == "vector4" -> PropertyType.VECTOR4
            qualifiedName.contains("curve") || simpleName == "curve" -> PropertyType.CURVE
            qualifiedName.contains("resource") || qualifiedName.contains("asset") -> PropertyType.RESOURCE
            kClass.isPrimitive || Number::class.isAssignableFrom(kClass.java) -> when {
                kClass == Int::class || kClass == Long::class || kClass == Short::class || kClass == Byte::class -> PropertyType.INT
                kClass == Float::class || kClass == Double::class -> PropertyType.FLOAT
                else -> PropertyType.FLOAT
            }
            kClass == Boolean::class -> PropertyType.BOOLEAN
            kClass == String::class -> PropertyType.STRING
            kClass.isData || kClass.isSealedClass -> PropertyType.NESTED_OBJECT
            else -> PropertyType.UNKNOWN
        }
    }

    private fun parseJsonElement(element: kotlinx.serialization.json.JsonElement, descriptor: SerialDescriptor, module: SerializersModule): Any? {
        // Simplified parsing for nested objects in serialization mode
        return try {
            module.getSerializer(descriptor).deserialize(element)
        } catch (e: Exception) {
            null
        }
    }

    private fun formatDisplayName(name: String): String {
        return name.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace("_", " ").capitalize()
    }
}

// ============================================================
// Default Editor Registry Implementation
// ============================================================

class DefaultPropertyEditorRegistry : PropertyEditorRegistry {

    @Composable
    override fun editProperty(
        descriptor: PropertyDescriptor,
        target: Any,
        commandManager: CommandManager,
        modifier: Modifier = Modifier,
        depth: Int = 0
    ) {
        when (descriptor.type) {
            PropertyType.NESTED_OBJECT -> NestedObjectEditor(descriptor, target, commandManager, modifier, depth, this)
            PropertyType.BOOLEAN -> BooleanEditor(descriptor, target, commandManager, modifier)
            PropertyType.ENUM -> EnumEditor(descriptor, target, commandManager, modifier)
            PropertyType.COLOR -> ColorEditor(descriptor, target, commandManager, modifier)
            PropertyType.VECTOR2, PropertyType.VECTOR3, PropertyType.VECTOR4 -> VectorEditor(descriptor, target, commandManager, modifier)
            PropertyType.INT, PropertyType.FLOAT, PropertyType.DOUBLE -> NumberEditor(descriptor, target, commandManager, modifier)
            PropertyType.RESOURCE -> ResourceEditor(descriptor, target, commandManager, modifier)
            PropertyType.CURVE -> CurveEditor(descriptor, target, commandManager, modifier)
            PropertyType.STRING -> StringEditor(descriptor, target, commandManager, modifier)
            else -> UnknownEditor(descriptor, target, commandManager, modifier)
        }
    }
}

// ============================================================
// Editor Composables (Placeholders for Custom Widgets)
// ============================================================

@Composable
fun PropertyGrid(
    target: Any,
    commandManager: CommandManager,
    registry: PropertyEditorRegistry = remember { DefaultPropertyEditorRegistry() },
    extractor: (Any) -> List<PropertyDescriptor> = { PropertyExtractor.extractProperties(it) },
    modifier: Modifier = Modifier,
    title: String? = null
) {
    val properties = remember(target) { extractor(target) }

    Card(modifier = modifier.fillMaxWidth(), elevation = 2.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            title?.let {
                Text(text = it, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                properties.forEach { prop ->
                    registry.editProperty(prop, target, commandManager, Modifier.fillMaxWidth(), 0)
                }
            }
        }
    }
}

@Composable
private fun NestedObjectEditor(
    descriptor: PropertyDescriptor,
    target: Any,
    commandManager: CommandManager,
    modifier: Modifier,
    depth: Int,
    registry: PropertyEditorRegistry
) {
    val expanded = remember { mutableStateOf(true) }
    val childTarget = descriptor.getter(target)

    if (childTarget == null) {
        Text(text = "${descriptor.displayName}: Null", color = Color.Gray, modifier = modifier.fillMaxWidth())
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { expanded.value = !expanded.value }) {
                    Icon(
                        imageVector = if (expanded.value) Icons.Filled.ExpandMore else Icons.Filled.ArrowRight,
                        contentDescription = "Toggle"
                    )
                }
                Text(text = descriptor.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }

            if (expanded.value) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    descriptor.children.forEach { child ->
                        registry.editProperty(child, childTarget, commandManager, Modifier.fillMaxWidth(), depth + 1)
                    }
                }
            }
        }
    }
}

@Composable
fun BooleanEditor(descriptor: PropertyDescriptor, target: Any, commandManager: CommandManager, modifier: Modifier) {
    val currentValue = (descriptor.getter(target) as? Boolean) ?: false
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = descriptor.displayName)
        androidx.compose.material3.Switch(
            checked = currentValue,
            onCheckedChange = { newValue ->
                if (newValue != currentValue) {
                    commandManager.executeCommand(SetPropertyCommand(target, descriptor, newValue, currentValue))
                }
            },
            enabled = !descriptor.isReadOnly
        )
    }
}

@Composable
fun EnumEditor(descriptor: PropertyDescriptor, target: Any, commandManager: CommandManager, modifier: Modifier) {
    val currentValue = descriptor.getter(target)
    val enumClass = currentValue?.javaClass ?: descriptor.getter(target)?.javaClass
    val entries = enumClass?.kotlin?.enumConstants ?: emptyArray()

    var expanded by remember { mutableStateOf(false) }
    val displayName = descriptor.displayName

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = "$displayName: ", fontWeight = FontWeight.Normal)
        androidx.compose.material3.OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            enabled = !descriptor.isReadOnly
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = currentValue?.toString() ?: "None")
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
    }

    if (expanded) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(text = entry.name) },
                    onClick = {
                        if (entry != currentValue) {
                            commandManager.executeCommand(SetPropertyCommand(target, descriptor, entry, currentValue))
                        }
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun NumberEditor(descriptor: PropertyDescriptor, target: Any, commandManager: CommandManager, modifier: Modifier) {
    val currentValue = descriptor.getter(target) as? Number
    val isFloat = descriptor.type == PropertyType.FLOAT || descriptor.type == PropertyType.DOUBLE || currentValue is Float || currentValue is Double

    var text by remember { mutableStateOf(currentValue?.toString() ?: "") }

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = "${descriptor.displayName}: ", width = 0, weight = 1f)
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.width(120.dp),
            enabled = !descriptor.isReadOnly,
            singleLine = true,
            keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(
                keyboardType = if (isFloat) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Number,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            keyboardActions = androidx.compose.ui.text.input.KeyboardActions(onDone = {
                val newValue = try {
                    if (isFloat) text.toDouble() else text.toLong()
                } catch (e: NumberFormatException) {
                    currentValue
                }
                if (newValue != currentValue) {
                    commandManager.executeCommand(SetPropertyCommand(target, descriptor, newValue, currentValue))
                }
            })
        )
    }
    // TODO: Replace TextField with DragNumber/Slider custom component
}

@Composable
fun StringEditor(descriptor: PropertyDescriptor, target: Any, commandManager: CommandManager, modifier: Modifier) {
    val currentValue = (descriptor.getter(target) as? String) ?: ""
    var text by remember { mutableStateOf(currentValue) }

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = "${descriptor.displayName}: ", width = 0, weight = 1f)
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.width(200.dp),
            enabled = !descriptor.isReadOnly,
            keyboardActions = androidx.compose.ui.text.input.KeyboardActions(onDone = {
                if (text != currentValue) {
                    commandManager.executeCommand(SetPropertyCommand(target, descriptor, text, currentValue))
                }
            })
        )
    }
}

@Composable
fun ColorEditor(descriptor: PropertyDescriptor, target: Any, commandManager: CommandManager, modifier: Modifier) {
    val currentValue = descriptor.getter(target)
    // Assume currentValue is a data class Color(r,g,b,a) or Compose Color
    val color = (currentValue as? Color) ?: Color.Unspecified

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = descriptor.displayName)
        androidx.compose.material3.OutlinedButton(
            onClick = { /* TODO: Open ColorPicker Dialog */ },
            modifier = Modifier.size(40.dp, 24.dp),
            enabled = !descriptor.isReadOnly
        ) {
            androidx.compose.foundation.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .clip(androidx.compose.ui.shape.RoundedCornerShape(4.dp))
            )
        }
    }
    // TODO: Implement ColorPicker Dialog integration
}

@Composable
fun VectorEditor(descriptor: PropertyDescriptor, target: Any, commandManager: CommandManager, modifier: Modifier) {
    val currentValue = descriptor.getter(target)
    val components = when (currentValue) {
        is Vector3 -> floatArrayOf(currentValue.x, currentValue.y, currentValue.z)
        is Vector2 -> floatArrayOf(currentValue.x, currentValue.y)
        is Vector4 -> floatArrayOf(currentValue.x, currentValue.y, currentValue.z, currentValue.w)
        else -> floatArrayOf(0f)
    }

    var texts = remember { components.map { it.toString() }.toMutableList() }
    val labels = when (components.size) {
        2 -> listOf("X", "Y")
        3 -> listOf("X", "Y", "Z")
        4 -> listOf("X", "Y", "Z", "W")
        else -> List(components.size) { "V$it" }
    }

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = "${descriptor.displayName}: ", width = 0, weight = 1f)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            components.indices.forEach { i ->
                var localText by remember { mutableStateOf(texts[i]) }
                Text(text = "${labels[i]}:", fontSize = 10.sp)
                TextField(
                    value = localText,
                    onValueChange = {
                        localText = it
                        texts[i] = it
                    },
                    modifier = Modifier.width(50.dp),
                    enabled = !descriptor.isReadOnly,
                    singleLine = true,
                    keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    keyboardActions = androidx.compose.ui.text.input.KeyboardActions(onDone = {
                        val newVals = texts.map { it.toDoubleOrNull() ?: 0.0 }.toDoubleArray()
                        val newVec = when (currentValue) {
                            is Vector3 -> currentValue.copy(x = newVals[0].toFloat(), y = newVals[1].toFloat(), z = newVals[2].toFloat())
                            is Vector2 -> currentValue.copy(x = newVals[0].toFloat(), y = newVals[1].toFloat())
                            is Vector4 -> currentValue.copy(x = newVals[0].toFloat(), y = newVals[1].toFloat(), z = newVals[2].toFloat(), w = newVals[3].toFloat())
                            else -> currentValue
                        }
                        if (newVec != currentValue) {
                            commandManager.executeCommand(SetPropertyCommand(target, descriptor, newVec, currentValue))
                        }
                    })
                )
            }
        }
    }
}

@Composable
fun ResourceEditor(descriptor: PropertyDescriptor, target: Any, commandManager: CommandManager, modifier: Modifier) {
    val currentValue = descriptor.getter(target) as? String ?: "None"
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = descriptor.displayName)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = currentValue.takeLast(30), maxLines = 1, overflow = androidx.compose.ui.text.TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
            IconButton(onClick = { /* TODO: Open Asset Browser */ }) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Browse Assets")
            }
        }
    }
}

@Composable
fun CurveEditor(descriptor: PropertyDescriptor, target: Any, commandManager: CommandManager, modifier: Modifier) {
    val currentValue = descriptor.getter(target)
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = descriptor.displayName)
        androidx.compose.material3.OutlinedButton(
            onClick = { /* TODO: Open Curve Editor Dialog */ },
            enabled = !descriptor.isReadOnly
        ) {
            Text("Edit Curve...")
        }
    }
}

@Composable
fun UnknownEditor(descriptor: PropertyDescriptor, target: Any, commandManager: CommandManager, modifier: Modifier) {
    val value = descriptor.getter(target)?.toString() ?: "null"
    Text(text = "${descriptor.displayName}: $value (Unsupported Type)", color = Color.Gray, modifier = modifier.fillMaxWidth().padding(vertical = 4.dp))
}

// ============================================================
// Helper Data Classes (Mockups for Vector Types)
// ============================================================

@kotlinx.serialization.Serializable
data class Vector2(var x: Float = 0f, var y: Float = 0f)

@kotlinx.serialization.Serializable
data class Vector3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f)

@kotlinx.serialization.Serializable
data class Vector4(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 1f)

// ============================================================
// Simple Command Manager Implementation
// ============================================================

class SimpleCommandManager : CommandManager {
    private val undoStack = mutableListOf<PropertyCommand>()
    private val redoStack = mutableListOf<PropertyCommand>()
    private val maxStackSize = 100

    override val canUndo: Boolean get() = undoStack.isNotEmpty()
    override val canRedo: Boolean get() = redoStack.isNotEmpty()

    override fun executeCommand(command: PropertyCommand) {
        command.execute()
        undoStack.add(command)
        if (undoStack.size > maxStackSize) undoStack.removeAt(0)
        redoStack.clear()
    }

    override fun undo() {
        if (canUndo) {
            val command = undoStack.removeAt(undoStack.lastIndex)
            command.undo()
            redoStack.add(command)
        }
    }

    override fun redo() {
        if (canRedo) {
            val command = redoStack.removeAt(redoStack.lastIndex)
            command.execute()
            undoStack.add(command)
        }
    }
}

// ============================================================
// Usage Example / Preview (Optional)
// ============================================================

/*
@Preview
@Composable
fun PropertyGridPreview() {
    val manager = remember { SimpleCommandManager() }
    val data = remember { 
        MaterialData(
            name = "Gold",
            color = Color(1.0f, 0.84f, 0.0f),
            roughness = 0.1f,
            metalness = 1.0f,
            albedoMap = "textures/gold_albedo.png",
            normalMap = "textures/gold_normal.png"
        )
    }

    PropertyGrid(
        target = data,
        commandManager = manager,
        title = "Material Inspector"
    )
}

@Serializable
data class MaterialData(
    var name: String,
    var color: Color,
    var roughness: Float,
    var metalness: Float,
    var albedoMap: String,
    var normalMap: String
)
*/