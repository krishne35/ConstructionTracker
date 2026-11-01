package com.example.constructiontracker

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject
import java.security.Security
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbSettings(
    val host: String = "", val share: String = "", val folder: String = "",
    val user: String = "", val pass: String = "", val domain: String = ""
)

/** Settings are stored encrypted on the device, including the password. */
class SmbPrefs(ctx: Context) {
    private val sp = EncryptedSharedPreferences.create(
        ctx, "smb_settings",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    private fun s(k: String) = sp.getString(k, "") ?: ""
    fun autoOn() = sp.getBoolean("auto", false)
    fun setAuto(on: Boolean) { sp.edit().putBoolean("auto", on).apply() }
    fun schedule() = Schedule(sp.getBoolean("weekly", false), sp.getInt("day", 1), sp.getInt("hour", 0), sp.getInt("minute", 0))
    fun setSchedule(s: Schedule) {
        sp.edit().putBoolean("weekly", s.weekly).putInt("day", s.day).putInt("hour", s.hour).putInt("minute", s.minute).apply()
    }
    fun lastAuto() = s("lastAuto")
    fun setLastAuto(v: String) { sp.edit().putString("lastAuto", v).apply() }
    fun load() = SmbSettings(s("host"), s("share"), s("folder"), s("user"), s("pass"), s("domain"))
    fun save(c: SmbSettings) {
        sp.edit().putString("host", c.host).putString("share", c.share).putString("folder", c.folder)
            .putString("user", c.user).putString("pass", c.pass).putString("domain", c.domain).apply()
    }
}

object SmbBackup {
    const val PREFIX = "ConstructionTracker_"
    private var providerReady = false

    /** Android ships a cut-down "BC" provider; swap in the full one that SMB authentication needs. */
    @Synchronized
    private fun ensureProvider() {
        if (providerReady) return
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        providerReady = true
    }

    private fun <T> withShare(c: SmbSettings, block: (DiskShare) -> T): T {
        ensureProvider()
        require(c.host.isNotBlank() && c.share.isNotBlank()) { "Enter the server and the share name" }
        val auth = if (c.user.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(c.user.trim(), c.pass.toCharArray(), c.domain.trim())
        val cfg = com.hierynomus.smbj.SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS).withSoTimeout(20, TimeUnit.SECONDS).build()
        return SMBClient(cfg).use { client ->
            client.connect(c.host.trim()).use { conn ->
                conn.authenticate(auth).use { session ->
                    (session.connectShare(c.share.trim()) as DiskShare).use(block)
                }
            }
        }
    }

    private fun folderPath(c: SmbSettings) =
        c.folder.split('/', '\\').filter { it.isNotBlank() }.joinToString("\\")

    private fun ensureDir(d: DiskShare, c: SmbSettings): String {
        var p = ""
        c.folder.split('/', '\\').filter { it.isNotBlank() }.forEach {
            p = if (p.isEmpty()) it else "$p\\$it"
            if (!d.folderExists(p)) d.mkdir(p)
        }
        return p
    }

    fun test(c: SmbSettings) { withShare(c) { ensureDir(it, c) } }

    fun newName() = PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".json"

    /** Deletes the oldest backups so only the newest [keep] remain. */
    fun prune(c: SmbSettings, keep: Int) {
        withShare(c) { d ->
            val dir = folderPath(c)
            val names = d.list(dir).map { it.fileName }
                .filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sorted()
            names.dropLast(keep).forEach { d.rm(if (dir.isEmpty()) it else "$dir\\$it") }
        }
    }

    fun upload(c: SmbSettings, name: String, bytes: ByteArray) {
        withShare(c) { d ->
            val dir = ensureDir(d, c)
            val path = if (dir.isEmpty()) name else "$dir\\$name"
            d.openFile(path, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
                f.outputStream.use { it.write(bytes) }
            }
        }
    }

    fun downloadLatest(c: SmbSettings): Pair<String, ByteArray> = withShare(c) { d ->
        val dir = folderPath(c)
        if (dir.isNotEmpty() && !d.folderExists(dir)) error("Backup folder not found on the share")
        val latest = d.list(dir).map { it.fileName }
            .filter { it.startsWith(PREFIX) && it.endsWith(".json") }.maxOrNull()
            ?: error("No backup files found")
        val path = if (dir.isEmpty()) latest else "$dir\\$latest"
        val bytes = d.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null).use { f -> f.inputStream.use { it.readBytes() } }
        latest to bytes
    }
}

object BackupJson {
    class Data(val projects: List<Project>, val categories: List<Category>, val parties: List<Party>, val txns: List<Txn>)

    fun encode(p: List<Project>, c: List<Category>, pt: List<Party>, t: List<Txn>): ByteArray {
        val o = JSONObject().put("version", 2).put("exportedAt", System.currentTimeMillis())
        o.put("projects", JSONArray().also { a -> p.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("currency", it.currency).put("indian", it.indian).put("decimals", it.decimals))
        } })
        o.put("categories", JSONArray().also { a -> c.forEach { a.put(JSONObject().put("id", it.id).put("projectId", it.projectId).put("name", it.name)) } })
        o.put("parties", JSONArray().also { a ->
            pt.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("isPayer", it.isPayer).put("projectId", it.projectId)) } })
        o.put("txns", JSONArray().also { a ->
            t.forEach {
                a.put(JSONObject().put("id", it.id).put("projectId", it.projectId).put("kind", it.kind.name)
                    .put("categoryId", it.categoryId).put("payerId", it.payerId)
                    .put("payeeId", it.payeeId ?: JSONObject.NULL).put("amount", it.amount)
                    .put("note", it.note).put("timestamp", it.timestamp))
            }
        })
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(b: ByteArray): Data {
        val o = JSONObject(String(b, Charsets.UTF_8))
        fun <T> list(key: String, f: (JSONObject) -> T): List<T> {
            val a = o.getJSONArray(key)
            return (0 until a.length()).map { f(a.getJSONObject(it)) }
        }
        return Data(
            list("projects") { Project(it.getLong("id"), it.getString("name"), it.optString("currency", "₹"), it.optBoolean("indian", true), it.optInt("decimals", 2)) },
            list("categories") { Category(it.getLong("id"), it.optLong("projectId", 0), it.getString("name")) },
            list("parties") { Party(it.getLong("id"), it.getString("name"), it.getBoolean("isPayer"), it.optLong("projectId", 0)) },
            list("txns") {
                Txn(it.getLong("id"), it.getLong("projectId"), Kind.valueOf(it.getString("kind")),
                    it.getLong("categoryId"), it.getLong("payerId"),
                    if (it.isNull("payeeId")) null else it.getLong("payeeId"),
                    it.getDouble("amount"), it.getString("note"), it.getLong("timestamp"))
            })
    }
}
