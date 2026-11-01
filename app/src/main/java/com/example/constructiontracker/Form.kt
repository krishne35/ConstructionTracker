package com.example.constructiontracker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.*

/**
 * Free-text field with suggestions from earlier entries. Nothing is suggested until the first letter is
 * typed, and the suggestions are chips under the field (not a pop-up), so they never cover the keyboard.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SuggestField(label: String, value: String, all: List<String>, onChange: (String) -> Unit) {
    val typed = value.trim()
    val matches = if (typed.isEmpty()) emptyList() else
        all.filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
            .sortedBy { if (it.startsWith(typed, ignoreCase = true)) 0 else 1 }
            .take(8)
    val bring = remember { BringIntoViewRequester() }
    Column {
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
        if (matches.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).bringIntoViewRequester(bring).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                matches.forEach { name -> SuggestionChip({ onChange(name) }, { Text(name) }) }
            }
        }
    }
    LaunchedEffect(typed, matches.size) { if (matches.isNotEmpty()) bring.bringIntoView() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerField(
    label: String, selected: Long?, options: List<Pair<Long, String>>, addTitle: String,
    onPick: (Long?) -> Unit, onAdd: (String, (Long) -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { (id, name) -> DropdownMenuItem({ Text(name) }, { onPick(id); open = false }) }
            HorizontalDivider()
            DropdownMenuItem({ Text(addTitle) }, { open = false; adding = true },
                leadingIcon = { Icon(Icons.Default.Add, null) })
        }
    }
    if (adding) NameDialog(addTitle, { adding = false }) { n -> onAdd(n) { id -> onPick(id) }; adding = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnForm(
    vm: CostViewModel, editing: Txn?, projects: List<Project>, defaultProjectId: Long,
    allCats: List<Category>, allParties: List<Party>, onDismiss: () -> Unit
) {
    val init = editing?.dateTime() ?: LocalDateTime.now().withSecond(0).withNano(0)
    var pid by remember { mutableStateOf(editing?.projectId ?: defaultProjectId) }
    var projOpen by remember { mutableStateOf(false) }
    val cats = allCats.filter { it.projectId == pid }
    val payers = allParties.filter { it.isPayer && it.projectId == pid }
    val payees = allParties.filter { !it.isPayer && it.projectId == pid }
    val pFmt = projects.firstOrNull { it.id == pid }?.fmt() ?: Fmt()
    var kind by remember { mutableStateOf(editing?.kind ?: Kind.EXPENSE) }
    var item by remember { mutableStateOf(allCats.firstOrNull { it.id == editing?.categoryId }?.name ?: "") }
    var payerId by remember { mutableStateOf(editing?.payerId) }
    var payee by remember { mutableStateOf(allParties.firstOrNull { it.id == editing?.payeeId }?.name ?: "") }
    var amount by remember {
        mutableStateOf(editing?.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    var date by remember { mutableStateOf(init.toLocalDate()) }
    var time by remember { mutableStateOf(init.toLocalTime()) }
    var error by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Kind.entries.forEachIndexed { i, k ->
                    SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, 2)) {
                        Text(if (k == Kind.EXPENSE) "Expense" else "Income")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickDate = true }, Modifier.weight(1f)) {
                    Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(6.dp)); Text(date.toString())
                }
                OutlinedButton({ pickTime = true }, Modifier.weight(1f)) {
                    Icon(Icons.Default.Info, null); Spacer(Modifier.width(6.dp)); Text(time.toString())
                }
            }
            ExposedDropdownMenuBox(projOpen, { projOpen = it }) {
                OutlinedTextField(
                    value = projects.firstOrNull { it.id == pid }?.name ?: "", onValueChange = {}, readOnly = true,
                    label = { Text("Project") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(projOpen) },
                    modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(projOpen, { projOpen = false }) {
                    projects.forEach { p ->
                        DropdownMenuItem({ Text(p.name) }, {
                            if (p.id != pid) { pid = p.id; payerId = null }
                            projOpen = false
                        })
                    }
                }
            }
            SuggestField("Item", item, cats.map { it.name }) { item = it }
            OutlinedTextField(
                amount,
                { v ->
                    val d = v.filter { it.isDigit() || it == '.' }
                    val first = d.indexOf('.')
                    amount = if (first < 0) d else {
                        val tail = d.substring(first + 1).replace(".", "").take(pFmt.decimals)
                        if (pFmt.decimals == 0) d.substring(0, first) else d.substring(0, first + 1) + tail
                    }
                },
                Modifier.fillMaxWidth(), label = { Text("Amount") }, singleLine = true,
                prefix = { Text(pFmt.symbol) },
                visualTransformation = AmountTransformation(pFmt.indian),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            PickerField(if (kind == Kind.EXPENSE) "Owner" else "Funds from owner", payerId,
                payers.map { it.id to it.name }, "Add new owner",
                { payerId = it }, { n, done -> vm.addParty(n, true, pid, done) })
            SuggestField("Supplier (optional)", payee, payees.map { it.name }) { payee = it }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") })
            if (error) Text("Enter an item, choose an owner, and enter an amount above 0.", color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                if (editing != null) {
                    TextButton({ vm.deleteTxn(editing); onDismiss() }) { Text("Delete") }
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onDismiss) { Text("Cancel") }
                Button({
                    val p = payerId; val a = amount.replace(",", "").toDoubleOrNull()
                    if (item.isBlank() || p == null || a == null || a <= 0) error = true else {
                        val ts = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        vm.saveEntry(editing?.id ?: 0, pid, kind, item, p, payee, a, note.trim(), ts)
                        onDismiss()
                    }
                }) { Text("Save") }
            }
        }
    }

    if (pickDate) {
        val st = rememberDatePickerState(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickDate = false }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }
    if (pickTime) {
        val st = rememberTimePickerState(time.hour, time.minute, true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(st) },
            confirmButton = { TextButton({ time = LocalTime.of(st.hour, st.minute); pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton({ pickTime = false }) { Text("Cancel") } })
    }
}
