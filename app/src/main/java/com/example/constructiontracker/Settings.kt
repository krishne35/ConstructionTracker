package com.example.constructiontracker

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------- currency (per project)

/** Currency symbol, digit grouping and decimals. Each project has its own. */
data class Fmt(val symbol: String = "₹", val indian: Boolean = true, val decimals: Int = 2) {
    fun format(v: Double): String {
        val r = BigDecimal.valueOf(Math.abs(v)).setScale(decimals, RoundingMode.HALF_UP)
        val plain = r.toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = if (decimals > 0) "." + plain.substringAfter('.') else ""
        val grouped = if (indian) groupIndian(intPart) else groupInternational(intPart)
        return (if (v < 0 && r.signum() != 0) "-" else "") + symbol + grouped + frac
    }
}

private fun groupIndian(s: String): String {
    if (s.length <= 3) return s
    var rest = s.dropLast(3)
    val parts = mutableListOf<String>()
    while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
    if (rest.isNotEmpty()) parts.add(0, rest)
    return parts.joinToString(",") + "," + s.takeLast(3)
}

private fun groupInternational(s: String): String {
    val parts = mutableListOf<String>()
    var rest = s
    while (rest.length > 3) { parts.add(0, rest.takeLast(3)); rest = rest.dropLast(3) }
    if (rest.isNotEmpty()) parts.add(0, rest)
    return parts.joinToString(",")
}

fun Project.fmt() = Fmt(currency, indian, decimals)

val LocalFmt = compositionLocalOf { Fmt() }

/** One format for a group of entries: the project's own if they share it, else a neutral one without a symbol. */
fun uniformFmt(rows: List<Txn>, fmts: Map<Long, Fmt>, fallback: Fmt): Fmt {
    val s = rows.mapNotNull { fmts[it.projectId] }.toSet()
    return when (s.size) { 0 -> fallback; 1 -> s.first(); else -> Fmt("", true, 2) }
}

fun isMixed(rows: List<Txn>, fmts: Map<Long, Fmt>) = rows.mapNotNull { fmts[it.projectId] }.toSet().size > 1

// ---------------------------------------------------------------- balances shown in the banner

data class Bal(val name: String, val amount: Double, val payer: Boolean)

/** Paid by balance = funds added minus payments made. Payees are not included. */
fun balances(list: List<Txn>, names: Map<Long, String>): List<Bal> {
    fun n(id: Long) = (names[id] ?: "?").trim()
    return list.groupBy { n(it.payerId).lowercase() }.map { (_, rows) ->
        Bal(
            n(rows.first().payerId),
            rows.filter { it.kind == Kind.INCOME }.sumOf { it.amount } - rows.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount },
            true)
    }.sortedBy { it.name.lowercase() }
}

// ---------------------------------------------------------------- app-wide settings

val HEADER_PRESETS = listOf(
    "Ocean" to (Color(0xFF0D47A1) to Color(0xFF00897B)),
    "Forest" to (Color(0xFF1B5E20) to Color(0xFF689F38)),
    "Sunset" to (Color(0xFFE65100) to Color(0xFFC62828)),
    "Royal" to (Color(0xFF4A148C) to Color(0xFF283593)),
    "Rose" to (Color(0xFFAD1457) to Color(0xFFEF6C00)),
    "Slate" to (Color(0xFF263238) to Color(0xFF546E7A))
)
private const val MATERIAL_YOU = 6
private const val CUSTOM = 7

object AppSettings {
    private var sp: SharedPreferences? = null

    var headerPreset by mutableIntStateOf(0)
    var customStart by mutableIntStateOf(0xFF0D47A1.toInt())
    var customEnd by mutableIntStateOf(0xFF00897B.toInt())
    var exportSummary by mutableStateOf(true)
    var dateStyle by mutableIntStateOf(0)

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        sp = p
        headerPreset = p.getInt("headerPreset", 0)
        customStart = p.getInt("customStart", customStart)
        customEnd = p.getInt("customEnd", customEnd)
        exportSummary = p.getBoolean("exportSummary", true)
        dateStyle = p.getInt("dateStyle", 0)
    }

    fun save() {
        sp?.edit()?.putInt("headerPreset", headerPreset)?.putInt("customStart", customStart)
            ?.putInt("customEnd", customEnd)?.putBoolean("exportSummary", exportSummary)
            ?.putInt("dateStyle", dateStyle)?.apply()
    }

    fun headerColors(primary: Color, tertiary: Color): Pair<Color, Color> = when {
        headerPreset in HEADER_PRESETS.indices -> HEADER_PRESETS[headerPreset].second
        headerPreset == MATERIAL_YOU -> primary to tertiary
        else -> Color(customStart) to Color(customEnd)
    }
}

// ---------------------------------------------------------------- colour wheel

private val hueColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

/** Hue and saturation wheel with a brightness slider and an RGB readout. */
@Composable
fun ColorWheelPicker(initial: Color, onColor: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val latest by rememberUpdatedState(onColor)
    val color = Color.hsv(hue, sat, value)
    val outline = MaterialTheme.colorScheme.onSurface

    fun pick(p: Offset) {
        val cx = box.width / 2f
        val cy = box.height / 2f
        val r = minOf(cx, cy)
        if (r <= 0f) return
        val dx = p.x - cx
        val dy = p.y - cy
        sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
        hue = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
        latest(Color.hsv(hue, sat, value))
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier.size(220.dp).onSizeChanged { box = it }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pick(down.position)
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { if (it.pressed) { pick(it.position); it.consume() } }
                    } while (ev.changes.any { it.pressed })
                }
            }
        ) {
            val r = size.minDimension / 2f
            drawCircle(Brush.sweepGradient(hueColors))
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent)))
            drawCircle(Color.Black.copy(alpha = 1f - value))
            drawCircle(outline.copy(alpha = 0.3f), style = Stroke(2.dp.toPx()))
            val ang = Math.toRadians(hue.toDouble())
            val c = Offset(center.x + (sat * r * cos(ang)).toFloat(), center.y + (sat * r * sin(ang)).toFloat())
            drawCircle(Color.White, radius = 11.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
            drawCircle(color, radius = 8.dp.toPx(), center = c)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness", style = MaterialTheme.typography.labelMedium)
            Slider(value, { value = it; latest(Color.hsv(hue, sat, it)) }, Modifier.weight(1f).padding(start = 12.dp))
        }
        Text(
            String.format("#%06X    R %d   G %d   B %d", color.toArgb() and 0xFFFFFF,
                (color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt()),
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Swatch(name: String, a: Color, b: Color, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b)))
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier))
        Text(name, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun AppearanceCard() {
    val scheme = MaterialTheme.colorScheme
    var target by remember { mutableIntStateOf(0) }
    var resetKey by remember { mutableIntStateOf(0) }
    val (c1, c2) = AppSettings.headerColors(scheme.primary, scheme.tertiary)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Top bar colours", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(c1, c2))))
            Text("Pick a ready-made look, or drag on the wheel for any colour you like.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HEADER_PRESETS.forEachIndexed { i, (name, c) ->
                    Swatch(name, c.first, c.second, AppSettings.headerPreset == i) {
                        AppSettings.headerPreset = i
                        AppSettings.customStart = c.first.toArgb(); AppSettings.customEnd = c.second.toArgb()
                        resetKey++; AppSettings.save()
                    }
                }
                Swatch("Material You", scheme.primary, scheme.tertiary, AppSettings.headerPreset == MATERIAL_YOU) {
                    AppSettings.headerPreset = MATERIAL_YOU
                    AppSettings.customStart = scheme.primary.toArgb(); AppSettings.customEnd = scheme.tertiary.toArgb()
                    resetKey++; AppSettings.save()
                }
                Swatch("Custom", Color(AppSettings.customStart), Color(AppSettings.customEnd), AppSettings.headerPreset == CUSTOM) {
                    AppSettings.headerPreset = CUSTOM; AppSettings.save()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Start colour", "End colour").forEachIndexed { i, l ->
                    FilterChip(target == i, { target = i; resetKey++ }, { Text(l) }, leadingIcon = {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(if (i == 0) AppSettings.customStart else AppSettings.customEnd)))
                    })
                }
            }
            // Re-created only when a preset or the Start/End choice changes, never while dragging.
            key(target, resetKey) {
                ColorWheelPicker(Color(if (target == 0) AppSettings.customStart else AppSettings.customEnd)) { c ->
                    if (target == 0) AppSettings.customStart = c.toArgb() else AppSettings.customEnd = c.toArgb()
                    AppSettings.headerPreset = CUSTOM
                    AppSettings.save()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- currency UI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyOptions(fmt: Fmt, onChange: (Fmt) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Preview: " + fmt.format(12345678.5), style = MaterialTheme.typography.bodyMedium)
        Text("Symbol", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("₹", "$", "€", "£", "").forEach { s ->
                FilterChip(fmt.symbol == s, { onChange(fmt.copy(symbol = s)) }, { Text(if (s.isEmpty()) "None" else s) })
            }
        }
        Text("Digit grouping", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Indian (1,00,000)", "International (100,000)").forEachIndexed { i, l ->
                SegmentedButton(fmt.indian == (i == 0), { onChange(fmt.copy(indian = i == 0)) },
                    SegmentedButtonDefaults.itemShape(i, 2)) { Text(l, style = MaterialTheme.typography.labelSmall) }
            }
        }
        Text("Decimals", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("None" to 0, "2 digits" to 2).forEachIndexed { i, (l, d) ->
                SegmentedButton(fmt.decimals == d, { onChange(fmt.copy(decimals = d)) },
                    SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
            }
        }
    }
}

@Composable
fun CurrencyCard(vm: CostViewModel, project: Project?) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Currency", style = MaterialTheme.typography.titleMedium)
            if (project == null) {
                Text("Create a project to set its currency.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("For ${project.name} only. Pick another project on the Projects tab to change its currency.",
                    style = MaterialTheme.typography.bodySmall)
                CurrencyOptions(project.fmt()) { vm.updateProjectFormat(project, it) }
            }
        }
    }
}

@Composable
fun NewProjectDialog(onDismiss: () -> Unit, onOk: (String, Fmt) -> Unit) {
    var name by remember { mutableStateOf("") }
    var fmt by remember { mutableStateOf(Fmt()) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("New project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
                CurrencyOptions(fmt) { fmt = it }
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) onOk(name.trim(), fmt) }) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptions() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Summary tables", style = MaterialTheme.typography.titleSmall)
            Text("Totals, spending by item and balance by owner", style = MaterialTheme.typography.bodySmall)
        }
        Switch(AppSettings.exportSummary, { AppSettings.exportSummary = it; AppSettings.save() })
    }
    Text("Date format", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("31-10-2026", "2026-10-31").forEachIndexed { i, l ->
            SegmentedButton(AppSettings.dateStyle == i, { AppSettings.dateStyle = i; AppSettings.save() },
                SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
        }
    }
    Text("Amounts use each project's own currency format.", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun EditCurrencyDialog(initial: Fmt, onDismiss: () -> Unit, onOk: (Fmt) -> Unit) {
    var fmt by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Currency and format") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { CurrencyOptions(fmt) { fmt = it } } },
        confirmButton = { TextButton({ onOk(fmt) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun EditProjectDialog(project: Project, onDismiss: () -> Unit, onOk: (String, Fmt) -> Unit) {
    var name by remember { mutableStateOf(project.name) }
    var fmt by remember { mutableStateOf(project.fmt()) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Edit project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
                CurrencyOptions(fmt) { fmt = it }
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) onOk(name.trim(), fmt) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** Shows grouping commas while typing: 1234567 appears as 12,34,567 (Indian) or 1,234,567. */
class AmountTransformation(private val indian: Boolean) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val dot = raw.indexOf('.')
        val intPart = if (dot >= 0) raw.substring(0, dot) else raw
        val grouped = if (indian) groupIndian(intPart) else groupInternational(intPart)
        val out = grouped + raw.substring(intPart.length)
        val o2t = IntArray(raw.length + 1)
        val t2o = IntArray(out.length + 1)
        var o = 0
        for (t in out.indices) {
            if (t < grouped.length && out[t] == ',') {
                t2o[t] = o
            } else {
                o2t[o] = t; t2o[t] = o; o++
            }
        }
        o2t[raw.length] = out.length
        t2o[out.length] = raw.length
        return androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(out),
            object : androidx.compose.ui.text.input.OffsetMapping {
                override fun originalToTransformed(offset: Int) = o2t[offset.coerceIn(0, raw.length)]
                override fun transformedToOriginal(offset: Int) = t2o[offset.coerceIn(0, out.length)]
            })
    }
}
