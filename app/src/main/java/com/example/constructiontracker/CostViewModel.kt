package com.example.constructiontracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CostViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    private val dao = db.dao()

    val projects = dao.projects().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories = dao.categories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val parties = dao.parties().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedId = MutableStateFlow<Long?>(null)
    /** Every transaction across all projects. */
    val txns = dao.allTxns().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            projects.collect { list ->
                if (list.none { it.id == selectedId.value }) selectedId.value = list.firstOrNull()?.id
            }
        }
    }

    fun select(id: Long) { selectedId.value = id }
    fun addProject(name: String, f: Fmt = Fmt()) {
        viewModelScope.launch {
            selectedId.value = dao.insertProject(Project(name = name, currency = f.symbol, indian = f.indian, decimals = f.decimals))
        }
    }
    fun updateProjectFormat(p: Project, f: Fmt) {
        viewModelScope.launch { dao.updateProject(p.copy(currency = f.symbol, indian = f.indian, decimals = f.decimals)) }
    }
    fun deleteProject(p: Project) { viewModelScope.launch { dao.deleteProject(p) } }
    fun saveTxn(t: Txn) { viewModelScope.launch { dao.upsertTxn(t) } }
    fun deleteTxn(t: Txn) { viewModelScope.launch { dao.deleteTxn(t) } }
    fun addParty(name: String, isPayer: Boolean, projectId: Long = 0, done: (Long) -> Unit = {}) {
        viewModelScope.launch { done(dao.insertParty(Party(name = name, isPayer = isPayer, projectId = projectId))) }
    }
    fun deleteParty(p: Party, onInUse: () -> Unit) {
        viewModelScope.launch { if (dao.partyUse(p.id) > 0) onInUse() else dao.deleteParty(p) }
    }

    /** Items and payees are typed names: reuse an existing one (any capitalisation) or create it. */
    fun saveEntry(id: Long, projectId: Long, kind: Kind, item: String, payerId: Long,
                  payee: String, amount: Double, note: String, ts: Long) {
        viewModelScope.launch {
            val name = item.trim()
            val catId = dao.categoryByName(name, projectId)?.id
                ?: dao.insertCategory(Category(projectId = projectId, name = name))
            val pn = payee.trim()
            val payeeId = if (pn.isEmpty()) null
                else (dao.partyByName(pn, false, projectId)?.id
                    ?: dao.insertParty(Party(name = pn, isPayer = false, projectId = projectId)))
            dao.upsertTxn(Txn(id, projectId, kind, catId, payerId, payeeId, amount, note, ts))
        }
    }

    suspend fun backupBytes(): ByteArray =
        BackupJson.encode(dao.projectsNow(), dao.categoriesNow(), dao.partiesNow(), dao.txnsNow())

    suspend fun restore(bytes: ByteArray) {
        val d = BackupJson.decode(bytes)
        db.withTransaction {
            dao.clearTxns(); dao.clearProjects(); dao.clearCategories(); dao.clearParties()
            dao.putProjects(d.projects); dao.putCategories(d.categories)
            dao.putParties(d.parties); dao.putTxns(d.txns)
        }
    }

    /** projectId = null exports every project into one workbook. */
    suspend fun exportXlsx(projectId: Long?): ByteArray {
        val ps = dao.projectsNow().filter { projectId == null || it.id == projectId }
        return Xlsx.build(ps, dao.txnsNow().groupBy { it.projectId },
            dao.categoriesNow().associate { it.id to it.name }, dao.partiesNow().associate { it.id to it.name },
            Xlsx.Options(AppSettings.exportSummary, AppSettings.dateStyle))
    }

    fun updateProject(p: Project, name: String, f: Fmt) {
        viewModelScope.launch { dao.updateProject(p.copy(name = name, currency = f.symbol, indian = f.indian, decimals = f.decimals)) }
    }

    /** Renames an owner or supplier everywhere it appears (one row per project it belongs to). */
    fun renameParties(list: List<Party>, name: String) {
        viewModelScope.launch { list.forEach { dao.updateParty(it.copy(name = name)) } }
    }

    /** Adds entries from a workbook written by Export to Excel. Nothing existing is changed or deleted. */
    suspend fun importXlsx(bytes: ByteArray): String {
        val sheets = XlsxImport.parse(bytes)
        if (sheets.isEmpty()) error("No sheets in the expected format were found")
        var created = 0
        var added = 0
        var dup = 0
        var skipped = 0
        db.withTransaction {
            val projectList = dao.projectsNow().toMutableList()
            val catNames = dao.categoriesNow().associate { it.id to it.name }.toMutableMap()
            val partyNames = dao.partiesNow().associate { it.id to it.name }.toMutableMap()
            val allTxns = dao.txnsNow()
            for (sh in sheets) {
                skipped += sh.skipped
                val wanted = sh.name.trim()
                var proj = projectList.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
                if (proj == null) {
                    val id = dao.insertProject(Project(name = wanted, currency = sh.fmt.symbol, indian = sh.fmt.indian, decimals = sh.fmt.decimals))
                    proj = Project(id, wanted, sh.fmt.symbol, sh.fmt.indian, sh.fmt.decimals)
                    projectList.add(proj)
                    created++
                }
                val pid = proj.id
                fun key(ts: Long, k: Kind, item: String, owner: String, amount: Double) =
                    "$ts|$k|${item.trim().lowercase()}|${owner.trim().lowercase()}|${Math.round(amount * 100)}"
                val existing = allTxns.filter { it.projectId == pid }
                    .map { key(it.timestamp, it.kind, catNames[it.categoryId] ?: "", partyNames[it.payerId] ?: "", it.amount) }
                    .toHashSet()
                for (r in sh.rows) {
                    if (key(r.ts, r.kind, r.item, r.owner, r.amount) in existing) { dup++; continue }
                    val catId = dao.categoryByName(r.item, pid)?.id
                        ?: dao.insertCategory(Category(projectId = pid, name = r.item)).also { catNames[it] = r.item }
                    val ownerId = dao.partyByName(r.owner, true, pid)?.id
                        ?: dao.insertParty(Party(name = r.owner, isPayer = true, projectId = pid)).also { partyNames[it] = r.owner }
                    val supplierId = if (r.supplier.isBlank()) null else
                        (dao.partyByName(r.supplier, false, pid)?.id
                            ?: dao.insertParty(Party(name = r.supplier, isPayer = false, projectId = pid)).also { partyNames[it] = r.supplier })
                    dao.upsertTxn(Txn(0, pid, r.kind, catId, ownerId, supplierId, r.amount, r.note, r.ts))
                    added++
                }
            }
        }
        return buildString {
            append("Imported $added entries")
            if (created > 0) append(" and $created new project" + (if (created > 1) "s" else ""))
            if (dup > 0) append(". Skipped $dup already in the app")
            if (skipped > 0) append(". Skipped $skipped rows with a missing date, item or amount")
        }
    }
}
