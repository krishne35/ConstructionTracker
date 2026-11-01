package com.example.constructiontracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter

/** projectNames = null hides the project label. Each row uses its own project's currency format. */
@Composable
fun DailyScreen(
    list: List<Txn>, cats: Map<Long, String>, parties: Map<Long, String>,
    projectNames: Map<Long, String>?, projectFmts: Map<Long, Fmt>, onClick: (Txn) -> Unit
) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No entries for this period") }
        return
    }
    val base = LocalFmt.current
    val byDay = list.groupBy { it.dateTime().toLocalDate() }.toSortedMap(reverseOrder())
    val dayFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, EEE")
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
        byDay.forEach { (day, rows) ->
            item(key = "h$day") {
                CompositionLocalProvider(LocalFmt provides uniformFmt(rows, projectFmts, base)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), Arrangement.SpaceBetween) {
                            Text(day.format(dayFmt), style = MaterialTheme.typography.labelLarge)
                            val e = rows.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }
                            val i = rows.filter { it.kind == Kind.INCOME }.sumOf { it.amount }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (i > 0) Text("In ${money(i)}", color = CreditGreen, style = MaterialTheme.typography.labelMedium)
                                if (e > 0) Text("Out ${money(e)}", color = DebitRed, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            items(rows, key = { it.id }) { t ->
                val overline: (@Composable () -> Unit)? = if (projectNames != null) {
                    { Text(projectNames[t.projectId] ?: "", color = MaterialTheme.colorScheme.primary) }
                } else null
                CompositionLocalProvider(LocalFmt provides (projectFmts[t.projectId] ?: base)) {
                    ListItem(
                        modifier = Modifier.clickable { onClick(t) },
                        overlineContent = overline,
                        headlineContent = { Text(cats[t.categoryId] ?: "?") },
                        supportingContent = {
                            Text(buildString {
                                append(parties[t.payerId] ?: "?")
                                t.payeeId?.let { append(" → ${parties[it] ?: "?"}") }
                                if (t.note.isNotBlank()) append("\n${t.note}")
                            })
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                val exp = t.kind == Kind.EXPENSE
                                Text(
                                    (if (exp) "-" else "+") + money(t.amount),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (exp) DebitRed else CreditGreen)
                                Text(t.dateTime().format(timeFmt), style = MaterialTheme.typography.labelSmall)
                            }
                        })
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    list: List<Txn>, cats: Map<Long, String>, parties: Map<Long, String>, projects: Map<Long, String>,
    projectFmts: Map<Long, Fmt>, onClick: (Txn) -> Unit
) {
    var kind by remember { mutableStateOf(Kind.EXPENSE) }
    var by by remember { mutableIntStateOf(0) }
    // Tapping a row switches to just that item, owner, supplier or project.
    var selected by remember(kind, by) { mutableStateOf<String?>(null) }
    val nameOf: (Txn) -> String = {
        when (by) {
            0 -> cats[it.categoryId]
            1 -> parties[it.payerId]
            2 -> it.payeeId?.let { id -> parties[id] }
            else -> projects[it.projectId]
        } ?: "Unspecified"
    }
    val groups = list.filter { it.kind == kind }
        .groupBy(nameOf)
        .map { it.key to it.value.sumOf { x -> x.amount } }
        .sortedByDescending { it.second }
    val sum = groups.sumOf { it.second }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Kind.entries.forEachIndexed { i, k ->
                SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, 2)) {
                    Text(if (k == Kind.EXPENSE) "Expense" else "Income")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Item", "Owners", "Suppliers", "Project").forEachIndexed { i, l ->
                SegmentedButton(by == i, { by = i }, SegmentedButtonDefaults.itemShape(i, 4)) { Text(l) }
            }
        }
        val sel = selected
        if (sel != null) {
            val rows = list.filter { it.kind == kind && nameOf(it) == sel }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ selected = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chart") }
                Column(Modifier.weight(1f)) {
                    Text(sel, style = MaterialTheme.typography.titleMedium)
                    Text("${rows.size} entries", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    money(rows.sumOf { it.amount }), style = MaterialTheme.typography.titleMedium,
                    color = if (kind == Kind.EXPENSE) DebitRed else CreditGreen)
            }
            DailyScreen(rows, cats, parties, projects, projectFmts, onClick)
        } else if (sum <= 0) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No data for this selection") }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), Alignment.Center) {
                        Box(Modifier.size(220.dp), Alignment.Center) {
                            Canvas(Modifier.fillMaxSize().padding(24.dp)) {
                                val stroke = Stroke(width = 44.dp.toPx())
                                var start = -90f
                                groups.forEachIndexed { i, g ->
                                    val sw = (g.second / sum * 360).toFloat()
                                    drawArc(palette[i % palette.size], start, sw, false, style = stroke)
                                    start += sw
                                }
                            }
                            Text(money(sum), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                item { Text("Tap a row to see only its entries", style = MaterialTheme.typography.labelSmall) }
                items(groups.size) { i ->
                    val (name, v) = groups[i]
                    ListItem(
                        modifier = Modifier.clickable { selected = name },
                        leadingContent = { Box(Modifier.size(16.dp).background(palette[i % palette.size], CircleShape)) },
                        headlineContent = { Text(name) },
                        supportingContent = { Text("%.1f%%".format(v / sum * 100)) },
                        trailingContent = { Text(money(v)) })
                }
            }
        }
    }
}

/** Same name in several projects is combined into one row. */
@Composable
fun PeopleScreen(
    vm: CostViewModel, parties: List<Party>, txns: List<Txn>,
    projects: List<Project>, defaultProjectId: Long?
) {
    var adding by remember { mutableStateOf<Boolean?>(null) }
    var renaming by remember { mutableStateOf<List<Party>?>(null) }
    val totalSpent = txns.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
        for (payer in listOf(true, false)) {
            item(key = "head$payer") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (payer) "Owners" else "Suppliers", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton({ adding = payer }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add") }
                }
            }
            val groups = parties.filter { it.isPayer == payer }
                .groupBy { it.name.trim().lowercase() }.values.sortedBy { it.first().name.lowercase() }
            if (groups.isEmpty()) item(key = "none$payer") { Text("None yet", Modifier.padding(16.dp, 4.dp)) }
            items(groups, key = { (if (payer) "pay-" else "rcv-") + it.first().name.trim().lowercase() }) { g ->
                val ids = g.map { it.id }.toSet()
                val spent = txns.filter {
                    it.kind == Kind.EXPENSE && (if (payer) it.payerId in ids else it.payeeId in ids)
                }.sumOf { it.amount }
                val funds = if (payer) txns.filter { it.kind == Kind.INCOME && it.payerId in ids }.sumOf { it.amount } else 0.0
                ListItem(
                    modifier = Modifier.clickable { renaming = g },
                    headlineContent = { Text(g.first().name) },
                    supportingContent = {
                        if (payer) Text(
                            "Funds ${money(funds)} · Paid ${money(spent)}\n" +
                                "%.0f%% of spending".format(if (totalSpent > 0) spent / totalSpent * 100 else 0.0))
                    },
                    trailingContent = {
                        if (payer) {
                            val bal = funds - spent
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(bal), style = MaterialTheme.typography.titleMedium,
                                    color = if (bal < 0) DebitRed else CreditGreen)
                                Text(if (bal < 0) "to reimburse" else "funds left", style = MaterialTheme.typography.labelSmall)
                            }
                        } else Text(money(spent), style = MaterialTheme.typography.titleMedium, color = DebitRed)
                    })
                HorizontalDivider()
            }
        }
    }

    renaming?.let { g ->
        RenameDialog("Rename ${g.first().name}", g.first().name, { renaming = null }) { n -> vm.renameParties(g, n); renaming = null }
    }
    adding?.let { isPayer ->
        AddPartyDialog(if (isPayer) "New owner" else "New supplier", projects, defaultProjectId, { adding = null }) { name, pid ->
            vm.addParty(name, isPayer, pid); adding = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPartyDialog(
    title: String, projects: List<Project>, defaultId: Long?,
    onDismiss: () -> Unit, onOk: (String, Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var pid by remember { mutableStateOf(defaultId ?: projects.firstOrNull()?.id) }
    var open by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                ExposedDropdownMenuBox(open, { open = it }) {
                    OutlinedTextField(
                        projects.firstOrNull { it.id == pid }?.name ?: "", {}, readOnly = true,
                        label = { Text("Project") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.menuAnchor())
                    ExposedDropdownMenu(open, { open = false }) {
                        projects.forEach { p -> DropdownMenuItem({ Text(p.name) }, { pid = p.id; open = false }) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ val p = pid; if (name.isNotBlank() && p != null) onOk(name.trim(), p) }) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun NameDialog(title: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton({ if (name.isNotBlank()) onOk(name.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton({ if (name.isNotBlank()) onOk(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
