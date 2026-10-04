package com.exam.app.data

import android.content.Context
import com.exam.app.models.Attempt
import com.exam.app.models.TestMeta
import com.exam.app.models.TestPaper
import com.google.gson.Gson
import java.io.File

/** Abhi kaun logged-in hai (Firebase uid ya "local" guest). Har user ka data alag folder me rehta hai. */
object Session {
    @Volatile var uid: String = "local"
    @Volatile var name: String = "Guest"
    @Volatile var email: String = ""
    @Volatile var cloud: Boolean = false
}

class UserStore(private val ctx: Context) {
    private val gson = Gson()

    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "x" }

    fun root(): File = File(ctx.filesDir, "users/${safe(Session.uid)}").apply { mkdirs() }
    private fun testsDir(): File = File(root(), "tests").apply { mkdirs() }
    private fun attemptsDir(): File = File(root(), "attempts").apply { mkdirs() }

    fun testDir(id: String): File = File(testsDir(), safe(id))
    fun mediaDir(id: String): File = File(testDir(id), "media").apply { mkdirs() }
    /** mkdirs kiye bina media path (web server ke liye). */
    fun mediaPath(id: String): File = File(testDir(id), "media")

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    // ---------- Tests ----------
    fun saveTest(paper: TestPaper) {
        val dir = testDir(paper.meta.id).apply { mkdirs() }
        writeAtomic(File(dir, "test.json"), gson.toJson(paper))
        writeAtomic(File(dir, "meta.json"), gson.toJson(paper.meta))
    }

    fun loadTest(id: String): TestPaper? = try {
        val f = File(testDir(id), "test.json")
        if (f.isFile) gson.fromJson(f.readText(Charsets.UTF_8), TestPaper::class.java) else null
    } catch (e: Exception) { null }

    fun listTests(): List<TestMeta> {
        val out = ArrayList<TestMeta>()
        testsDir().listFiles()?.forEach { d ->
            val f = File(d, "meta.json")
            if (f.isFile) {
                try { gson.fromJson(f.readText(Charsets.UTF_8), TestMeta::class.java)?.let { out.add(it) } } catch (_: Exception) {}
            }
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun deleteTest(id: String) { testDir(id).deleteRecursively() }

    // ---------- Attempts ----------
    fun saveAttempt(a: Attempt) {
        writeAtomic(File(attemptsDir(), safe(a.id) + ".json"), gson.toJson(a))
    }

    fun loadAttempt(id: String): Attempt? = try {
        val f = File(attemptsDir(), safe(id) + ".json")
        if (f.isFile) gson.fromJson(f.readText(Charsets.UTF_8), Attempt::class.java) else null
    } catch (e: Exception) { null }

    fun listAttempts(): List<Attempt> {
        val out = ArrayList<Attempt>()
        attemptsDir().listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try { gson.fromJson(f.readText(Charsets.UTF_8), Attempt::class.java)?.let { out.add(it) } } catch (_: Exception) {}
        }
        return out.sortedByDescending { it.startedAt }
    }

    fun findUnfinished(testId: String): Attempt? =
        listAttempts().firstOrNull { it.testId == testId && !it.finished }

    // ---------- Tracker (web) backup ----------
    fun trackerBackupFile(): File = File(root(), "tracker_backup.json")
}
