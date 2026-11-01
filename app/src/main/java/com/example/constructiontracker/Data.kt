package com.example.constructiontracker

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class Kind { EXPENSE, INCOME }

/** Each project carries its own currency symbol, digit grouping and decimals. */
@Entity data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val currency: String = "₹",
    val indian: Boolean = true,
    val decimals: Int = 2
)
/** Items belong to one project, so every new project starts with an empty list. */
@Entity data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "0") val projectId: Long = 0,
    val name: String
)

/** A person or account. isPayer = true -> shows in "Paid by"; false -> shows in "Paid to". */
/** A payer or payee. Each belongs to one project. */
@Entity data class Party(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isPayer: Boolean,
    @ColumnInfo(defaultValue = "0") val projectId: Long = 0
)

@Entity(
    foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"],
        childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val kind: Kind,
    val categoryId: Long,
    val payerId: Long,
    val payeeId: Long?,
    val amount: Double,
    val note: String,
    val timestamp: Long
)

@Dao
interface CostDao {
    @Query("SELECT * FROM Project ORDER BY name") fun projects(): Flow<List<Project>>
    @Query("SELECT * FROM Txn ORDER BY timestamp DESC") fun allTxns(): Flow<List<Txn>>
    @Query("SELECT * FROM Category ORDER BY name") fun categories(): Flow<List<Category>>
    @Query("SELECT * FROM Party ORDER BY name") fun parties(): Flow<List<Party>>
    @Query("SELECT * FROM Txn WHERE projectId = :pid ORDER BY timestamp DESC") fun txns(pid: Long): Flow<List<Txn>>
    @Query("SELECT COUNT(*) FROM Category") suspend fun categoryCount(): Int
    @Query("SELECT COUNT(*) FROM Txn WHERE payerId = :id OR payeeId = :id") suspend fun partyUse(id: Long): Int

    @Query("SELECT * FROM Project") suspend fun projectsNow(): List<Project>
    @Query("SELECT * FROM Category") suspend fun categoriesNow(): List<Category>
    @Query("SELECT * FROM Party") suspend fun partiesNow(): List<Party>
    @Query("SELECT * FROM Txn") suspend fun txnsNow(): List<Txn>
    @Query("SELECT * FROM Category WHERE name = :n COLLATE NOCASE AND projectId = :pid LIMIT 1")
    suspend fun categoryByName(n: String, pid: Long): Category?
    @Query("SELECT * FROM Party WHERE name = :n COLLATE NOCASE AND isPayer = :payer AND projectId = :pid LIMIT 1")
    suspend fun partyByName(n: String, payer: Boolean, pid: Long): Party?
    @Query("DELETE FROM Txn") suspend fun clearTxns()
    @Query("DELETE FROM Project") suspend fun clearProjects()
    @Query("DELETE FROM Category") suspend fun clearCategories()
    @Query("DELETE FROM Party") suspend fun clearParties()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProjects(l: List<Project>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCategories(l: List<Category>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putParties(l: List<Party>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTxns(l: List<Txn>)
    @Update suspend fun updateProject(p: Project)
    @Update suspend fun updateParty(p: Party)
    @Insert suspend fun insertProject(p: Project): Long
    @Insert suspend fun insertCategory(c: Category): Long
    @Insert suspend fun insertCategories(c: List<Category>)
    @Insert suspend fun insertParty(p: Party): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTxn(t: Txn)
    @Delete suspend fun deleteTxn(t: Txn)
    @Delete suspend fun deleteProject(p: Project)
    @Delete suspend fun deleteParty(p: Party)
}

/** Gives each project its own copy of the items and payees it already uses. Payers stay shared. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Category ADD COLUMN projectId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Party ADD COLUMN projectId INTEGER NOT NULL DEFAULT 0")

        val cats = mutableListOf<Triple<Long, Long, String>>()
        db.query("SELECT DISTINCT t.projectId, c.id, c.name FROM Txn t JOIN Category c ON c.id = t.categoryId").use {
            while (it.moveToNext()) cats.add(Triple(it.getLong(0), it.getLong(1), it.getString(2)))
        }
        for ((pid, oldId, name) in cats) {
            db.execSQL("INSERT INTO Category (projectId, name) VALUES (?, ?)", arrayOf<Any?>(pid, name))
            val newId = db.query("SELECT last_insert_rowid()").use { c -> c.moveToFirst(); c.getLong(0) }
            db.execSQL("UPDATE Txn SET categoryId = ? WHERE projectId = ? AND categoryId = ?", arrayOf<Any?>(newId, pid, oldId))
        }
        db.execSQL("DELETE FROM Category WHERE projectId = 0")

        val payees = mutableListOf<Triple<Long, Long, String>>()
        db.query("SELECT DISTINCT t.projectId, p.id, p.name FROM Txn t JOIN Party p ON p.id = t.payeeId").use {
            while (it.moveToNext()) payees.add(Triple(it.getLong(0), it.getLong(1), it.getString(2)))
        }
        for ((pid, oldId, name) in payees) {
            db.execSQL("INSERT INTO Party (name, isPayer, projectId) VALUES (?, 0, ?)", arrayOf<Any?>(name, pid))
            val newId = db.query("SELECT last_insert_rowid()").use { c -> c.moveToFirst(); c.getLong(0) }
            db.execSQL("UPDATE Txn SET payeeId = ? WHERE projectId = ? AND payeeId = ?", arrayOf<Any?>(newId, pid, oldId))
        }
        db.execSQL("DELETE FROM Party WHERE isPayer = 0 AND projectId = 0")
    }
}

/** Payers also become per-project: each project gets its own copy of the payers it used. */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val rows = mutableListOf<Triple<Long, Long, String>>()
        db.query("SELECT DISTINCT t.projectId, p.id, p.name FROM Txn t JOIN Party p ON p.id = t.payerId WHERE p.projectId = 0").use {
            while (it.moveToNext()) rows.add(Triple(it.getLong(0), it.getLong(1), it.getString(2)))
        }
        for ((pid, oldId, name) in rows) {
            db.execSQL("INSERT INTO Party (name, isPayer, projectId) VALUES (?, 1, ?)", arrayOf<Any?>(name, pid))
            val newId = db.query("SELECT last_insert_rowid()").use { c -> c.moveToFirst(); c.getLong(0) }
            db.execSQL("UPDATE Txn SET payerId = ? WHERE projectId = ? AND payerId = ?", arrayOf<Any?>(newId, pid, oldId))
        }
        db.execSQL("DELETE FROM Party WHERE isPayer = 1 AND projectId = 0")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Project ADD COLUMN currency TEXT NOT NULL DEFAULT '₹'")
        db.execSQL("ALTER TABLE Project ADD COLUMN indian INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE Project ADD COLUMN decimals INTEGER NOT NULL DEFAULT 2")
    }
}

@Database(entities = [Project::class, Category::class, Party::class, Txn::class], version = 5, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): CostDao

    companion object {
        @Volatile private var inst: AppDb? = null
        /** One shared instance for the app and the nightly backup worker. */
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "costs.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
