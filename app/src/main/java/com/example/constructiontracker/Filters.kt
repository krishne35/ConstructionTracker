package com.example.constructiontracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Applies on every page. Items are matched by name, ignoring capitalisation. */
data class Filters(
    val projects: Set<Long> = emptySet(),
    val kind: Kind? = null,
    val items: Set<String> = emptySet()
) {
    val count: Int get() = (if (projects.isNotEmpty()) 1 else 0) + (if (kind != null) 1 else 0) + (if (items.isNotEmpty()) 1 else 0)
}

fun Txn.matches(f: Filters, cats: Map<Long, String>, useProjects: Boolean = true): Boolean =
    (!useProjects || f.projects.isEmpty() || projectId in f.projects) &&
        (f.kind == null || kind == f.kind) &&
        (f.items.isEmpty() || (cats[categoryId]?.trim()?.lowercase() ?: "") in f.items)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    f: Filters, projects: List<Project>, cats: List<Category>,
    onChange: (Filters) -> Unit, showProjects: Boolean = true, onDismiss: () -> Unit
) {
    val itemNames = cats.filter { f.projects.isEmpty() || it.projectId in f.projects }
        .groupBy { it.name.trim().lowercase() }
        .map { it.key to it.value.first().name }
        .sortedBy { it.second.lowercase() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton({ onChange(Filters()) }) { Text("Clear all") }
            }

            Text("Type", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("All" to null, "Income" to Kind.INCOME, "Expense" to Kind.EXPENSE).forEachIndexed { i, (label, k) ->
                    SegmentedButton(f.kind == k, { onChange(f.copy(kind = k)) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }

            if (showProjects) {
                Text("Projects", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    projects.forEach { p ->
                        FilterChip(p.id in f.projects,
                            { onChange(f.copy(projects = if (p.id in f.projects) f.projects - p.id else f.projects + p.id)) },
                            { Text(p.name) })
                    }
                }
            }

            Text("Items", style = MaterialTheme.typography.labelLarge)
            if (itemNames.isEmpty()) Text("No items yet", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemNames.forEach { (key, name) ->
                    FilterChip(key in f.items,
                        { onChange(f.copy(items = if (key in f.items) f.items - key else f.items + key)) },
                        { Text(name) })
                }
            }

            Button(onDismiss, Modifier.fillMaxWidth()) { Text("Show results") }
        }
    }
}
