package com.example.constructiontracker

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { App(viewModel()) } }
        }
    }
}

val palette = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFFFDD835), Color(0xFF6D4C41)
)
val CreditGreen: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF66BB6A) else Color(0xFF2E7D32)
val DebitRed: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEF5350) else Color(0xFFC62828)
private val CreditLight = Color(0xFFA5D6A7)
private val DebitLight = Color(0xFFFF8A80)

/** Formats using the currency settings (symbol, Indian or international grouping, decimals). */
@Composable
fun money(v: Double): String = LocalFmt.current.format(v)
fun Txn.dateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())

/** How far the banner has shrunk while a list is scrolled. */
@Stable
class HeaderState {
    var collapsed by mutableFloatStateOf(0f)
    var range by mutableFloatStateOf(0f)
    val fraction: Float get() = if (range <= 0f) 0f else (collapsed / range).coerceIn(0f, 1f)
}

private fun Modifier.collapsible(state: HeaderState): Modifier = this
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val h = (p.height - state.collapsed).coerceIn(0f, p.height.toFloat()).toInt()
        layout(p.width, h) { p.placeRelative(0, 0) }
    }
    .clipToBounds()
    .graphicsLayer { alpha = 1f - state.fraction }

@Composable
fun App(vm: CostViewModel) {
    val projects by vm.projects.collectAsState()
    val selId by vm.selectedId.collectAsState()
    val txns by vm.txns.collectAsState()
    val cats by vm.categories.collectAsState()
    val parties by vm.parties.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var allTime by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(Filters()) }
    var showFilter by remember { mutableStateOf(false) }
    var editFmt by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var newProject by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val header = remember { HeaderState() }
    val connection = remember(header) {
        object : NestedScrollConnection {
            // Swiping up: shrink the banner before the list scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || header.range <= 0f) return Offset.Zero
                val newV = (header.collapsed - available.y).coerceIn(0f, header.range)
                val used = newV - header.collapsed
                header.collapsed = newV
                return Offset(0f, -used)
            }

            // Swiping down: once the list is at the top, bring the banner back.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f || header.collapsed <= 0f) return Offset.Zero
                val newV = (header.collapsed - available.y).coerceIn(0f, header.range)
                val used = header.collapsed - newV
                header.collapsed = newV
                return Offset(0f, used)
            }
        }
    }
    LaunchedEffect(tab) { header.collapsed = 0f }

    val current = projects.firstOrNull { it.id == selId }
    val projectNames = projects.associate { it.id to it.name }
    val catNames = cats.associate { it.id to it.name }
    val partyNames = parties.associate { it.id to it.name }
    val active = filters.projects.filter { it in projectNames }.toSet()
    val ef = filters.copy(projects = active)
    // The Projects tab picks its project with the chips, so a project filter is not counted there.
    val shownCount = if (tab == 1) ef.count - (if (ef.projects.isNotEmpty()) 1 else 0) else ef.count
    val baseLabel = when (active.size) {
        0 -> "All projects"
        1 -> projectNames[active.first()] ?: ""
        else -> "${active.size} projects"
    }
    val extra = listOfNotNull(
        ef.kind?.let { if (it == Kind.INCOME) "Income" else "Expenses" },
        if (ef.items.isNotEmpty()) "${ef.items.size} item" + (if (ef.items.size > 1) "s" else "") else null)
    val filterLabel = (listOf(baseLabel) + extra).joinToString(" · ")

    val monthTxns = if (allTime) txns else txns.filter { YearMonth.from(it.dateTime()) == month }
    val inMonth = monthTxns.filter { it.matches(ef, catNames) }
    val projList = monthTxns.filter { it.projectId == selId && it.matches(ef, catNames, false) }
    val statsList = inMonth
    val peopleTxns = txns.filter { it.matches(ef, catNames) }
    // Paid by balances ignore the type and item filters: balance is always funds minus payments.
    val balBase = if (tab == 1) txns.filter { it.projectId == selId } else txns.filter { active.isEmpty() || it.projectId in active }
    val peopleParties = parties.filter { active.isEmpty() || it.projectId in active }

    val projectFmts = projects.associate { it.id to it.fmt() }
    val firstFmt = projects.firstOrNull()?.fmt() ?: Fmt()
    val filterFmt = if (active.size == 1) projectFmts[active.first()] ?: firstFmt else firstFmt
    val homeFmt = uniformFmt(inMonth, projectFmts, firstFmt)
    val projFmt = current?.fmt() ?: firstFmt
    val statsFmt = uniformFmt(statsList, projectFmts, filterFmt)
    val peopleFmt = uniformFmt(peopleTxns, projectFmts, filterFmt)

    fun edit(t: Txn?) { editing = t; showForm = true }

    val hList = when (tab) { 0 -> inMonth; 1 -> projList; 2 -> statsList; 3 -> peopleTxns; else -> null }
    val hSub = when (tab) {
        0 -> if (ef.count == 0) "All projects · ${projects.size}" else filterLabel
        1 -> (listOf(current?.name ?: "No project") + extra).joinToString(" · ")
        2, 3 -> filterLabel
        else -> "Settings"
    }

    val hFmt = when (tab) { 0 -> homeFmt; 1 -> projFmt; 2 -> statsFmt; 3 -> peopleFmt; else -> firstFmt }
    val hMixed = hList != null && isMixed(hList, projectFmts)
    val hSubtitle = if (hMixed) "$hSub · mixed currencies" else hSub
    val hBals = if (hList != null) balances(balBase, partyNames) else emptyList()

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    "Home" to Icons.Default.Home, "Projects" to Icons.Default.Folder, "Stats" to Icons.Default.PieChart,
                    "People" to Icons.Default.Groups, "Settings" to Icons.Default.Settings
                ).forEachIndexed { i, (label, icon) ->
                    NavTab(tab == i, { tab = i }, icon, label)
                }
            }
        },
        floatingActionButton = {
            if (projects.isNotEmpty() && tab <= 3) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FloatingActionButton({ showFilter = true }) {
                        BadgedBox(badge = { if (shownCount > 0) Badge { Text("$shownCount") } }) {
                            Icon(Icons.Default.FilterList, "Filters")
                        }
                    }
                    if (tab <= 2) FloatingActionButton({ edit(null) }) { Icon(Icons.Default.Add, "Add entry") }
                }
            }
        }
    ) { pad ->
        CompositionLocalProvider(LocalFmt provides hFmt) {
        Column(Modifier.padding(pad).fillMaxSize().nestedScroll(connection)) {
            Header(header, hSubtitle, hList, hBals, if (tab <= 2) month else null, allTime, { month = it }, { allTime = it }) {
                if (tab == 1 && current != null) {
                    IconButton({ editFmt = true }) { Icon(Icons.Default.Edit, "Edit project", tint = Color.White) }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete project", tint = Color.White) }
                }
            }
            if (tab == 4) {
                SettingsScreen(vm, current)
            } else if (projects.isEmpty()) {
                EmptyState { newProject = true }
            } else {
                when (tab) {
                    0 -> DailyScreen(inMonth, catNames, partyNames, projectNames, projectFmts) { edit(it) }
                    1 -> {
                        ProjectChips(projects, selId, vm::select) { newProject = true }
                        DailyScreen(projList, catNames, partyNames, null, projectFmts) { edit(it) }
                    }
                    2 -> {
                        StatsScreen(statsList, catNames, partyNames, projectNames, projectFmts) { edit(it) }
                    }
                    else -> {
                        PeopleScreen(vm, peopleParties, peopleTxns, projects, if (active.size == 1) active.first() else selId)
                    }
                }
            }
        }
        }
    }

    if (showFilter) {
        val onProjects = tab == 1
        FilterSheet(
            if (onProjects) filters.copy(projects = emptySet()) else filters,
            projects,
            if (onProjects) cats.filter { it.projectId == selId } else cats,
            { filters = if (onProjects) it.copy(projects = filters.projects) else it },
            showProjects = !onProjects
        ) { showFilter = false }
    }
    if (editFmt && current != null) EditProjectDialog(current, { editFmt = false }) { n, f ->
        vm.updateProject(current, n, f); editFmt = false
    }
    if (newProject) NewProjectDialog({ newProject = false }) { n, f -> vm.addProject(n, f); newProject = false }
    if (confirmDelete && current != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${current.name}?") },
        text = { Text("All entries in this project will be deleted.") },
        confirmButton = { TextButton({ vm.deleteProject(current); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    if (showForm && projects.isNotEmpty()) TxnForm(
        vm, editing, projects, selId ?: projects.first().id, cats, parties
    ) { showForm = false }
}

@Composable
fun EmptyState(onCreate: () -> Unit) {
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Default.Construction, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Start your first project")
        Spacer(Modifier.height(12.dp))
        Button(onCreate) { Text("Create project") }
    }
}

@Composable
private fun Pill(label: String, amount: Double, color: Color, fg: Color, up: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(fg.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (up) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
            Text(money(amount), style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Gradient banner. The big balance block shrinks away as a list scrolls, leaving a slim bar
 * with the title, the net balance and the month selector.
 */
@Composable
fun Header(
    state: HeaderState, subtitle: String, list: List<Txn>?, balances: List<Bal>, month: YearMonth?, all: Boolean,
    onMonth: (YearMonth) -> Unit, onAll: (Boolean) -> Unit,
    actions: @Composable () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val (c1, c2) = AppSettings.headerColors(scheme.primary, scheme.tertiary)
    val light = (c1.luminance() + c2.luminance()) / 2f > 0.5f
    val fg = if (light) Color(0xFF1B1B1F) else Color.White
    val creditC = if (light) Color(0xFF1B5E20) else CreditLight
    val debitC = if (light) Color(0xFFB71C1C) else DebitLight
    val inc = list?.filter { it.kind == Kind.INCOME }?.sumOf { it.amount } ?: 0.0
    val exp = list?.filter { it.kind == Kind.EXPENSE }?.sumOf { it.amount } ?: 0.0

    Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(c1, c2)), RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(fg.copy(alpha = 0.16f), CircleShape), Alignment.Center) {
                    Icon(Icons.Default.Construction, null, tint = fg)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Construction Costs", style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.8f))
                }
                if (list != null) {
                    Text(
                        money(inc - exp), Modifier.graphicsLayer { alpha = state.fraction },
                        style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.Bold)
                }
                actions()
            }
            if (list != null) {
                Box(Modifier.collapsible(state)) {
                    Column(Modifier.onSizeChanged { state.range = it.height.toFloat() }) {
                        Spacer(Modifier.height(12.dp))
                        Text("Net balance", style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.8f))
                        Text(money(inc - exp), style = MaterialTheme.typography.headlineMedium, color = fg, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Income", inc, creditC, fg, false)
                            Pill("Expense", exp, debitC, fg, true)
                        }
                        if (balances.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Owner balances", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                balances.forEach { b -> BalChip(b, fg, creditC, debitC) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            } else {
                SideEffect { if (state.range != 0f) state.range = 0f }
            }
            if (month != null) {
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onMonth(month.minusMonths(1)) }, Modifier.size(40.dp), enabled = !all) {
                        Icon(Icons.Default.ChevronLeft, "Previous month", tint = fg.copy(alpha = if (all) 0.4f else 1f))
                    }
                    Text(
                        if (all) "All time" else month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium, color = fg)
                    IconButton({ onMonth(month.plusMonths(1)) }, Modifier.size(40.dp), enabled = !all) {
                        Icon(Icons.Default.ChevronRight, "Next month", tint = fg.copy(alpha = if (all) 0.4f else 1f))
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(fg.copy(alpha = if (all) 0.3f else 0.14f))
                            .clickable { onAll(!all) }.padding(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text("All time", style = MaterialTheme.typography.labelLarge, color = fg) }
                }
            }
        }
    }
}

@Composable
fun ProjectChips(projects: List<Project>, selected: Long?, onSelect: (Long) -> Unit, onNew: () -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(projects, key = { it.id }) { p -> FilterChip(p.id == selected, { onSelect(p.id) }, { Text(p.name) }) }
        item { AssistChip(onNew, { Text("New") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }) }
    }
}

@Composable
fun FilterRow(projects: List<Project>, selected: Set<Long>, onChange: (Set<Long>) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { FilterChip(selected.isEmpty(), { onChange(emptySet()) }, { Text("All projects") }) }
        items(projects, key = { it.id }) { p ->
            FilterChip(p.id in selected, { onChange(if (p.id in selected) selected - p.id else selected + p.id) }, { Text(p.name) })
        }
    }
}

@Composable
private fun BalChip(b: Bal, fg: Color, credit: Color, debit: Color) {
    val color = if (b.payer && b.amount >= 0) credit else debit
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(fg.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(if (b.payer) b.name else "→ " + b.name, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.85f), maxLines = 1)
        Text(money(b.amount), style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
    }
}

/** Bottom bar item with a rounded-square (squircle) selection that fades and widens when chosen. */
@Composable
fun RowScope.NavTab(selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.primaryContainer else Color.Transparent, label = "navBg")
    val fg by animateColorAsState(if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant, label = "navFg")
    val w by animateDpAsState(if (selected) 56.dp else 44.dp, label = "navWidth")
    Column(
        Modifier.weight(1f)
            .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(w, 38.dp).clip(RoundedCornerShape(16.dp)).background(bg), Alignment.Center) {
            Icon(icon, null, tint = fg)
        }
        Text(
            label, style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}
