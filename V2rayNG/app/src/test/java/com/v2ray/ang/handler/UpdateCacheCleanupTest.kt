package com.v2ray.ang.handler

import android.content.pm.PackageInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File

class UpdateCacheCleanupTest {
    @TempDir lateinit var cache: File
    private val packageName = "com.v2ray.ang"

    @Test
    fun removesOnlyOwnInstalledOrOlderReadyArchives() {
        val updates = File(cache, "updates").apply { mkdir() }
        val archives = mapOf(
            "update.apk" to archive("2.3.9"),
            "update-equal.apk" to archive("2.3.15.4"),
            "update-preview.apk" to archive("2.3.15.4-testpre.8"),
            "update-older.apk" to archive("2.3.15.3"),
            "update-future.apk" to archive("2.3.15.5"),
            "update-unknown.apk" to archive("unknown"),
            "update-overflow.apk" to archive("2.3.15.999999999999999"),
            "update-missing-version.apk" to archive(null),
            "update-other-package.apk" to archive("2.3.0", "com.example.other"),
            "update-corrupt.apk" to null
        )
        archives.keys.forEach { File(updates, it).writeText("synthetic archive") }
        File(updates, "pending-123.apk").writeText("active download")
        File(updates, "other.apk").writeText("other owner")
        File(updates, "update-not-apk.txt").writeText("other format")
        File(updates, "update-directory.apk").mkdir()
        val unrelated = File(cache, "unrelated.txt").apply { writeText("keep") }
        val inspected = mutableSetOf<String>()

        UpdateCheckerManager.cleanupInstalledApks(cache, packageName, "2.3.15.4", { file ->
            inspected.add(file.name)
            archives[file.name]
        })

        val removed = setOf("update.apk", "update-equal.apk", "update-preview.apk", "update-older.apk")
        assertEquals(archives.keys, inspected)
        assertEquals(archives.keys - removed + setOf("pending-123.apk", "other.apk", "update-not-apk.txt",
            "update-directory.apk"), updates.listFiles().orEmpty().map { it.name }.toSet())
        assertTrue(unrelated.exists())
    }

    @Test
    fun unknownInstalledVersionPreservesEverythingWithoutParsingArchives() {
        val ready = File(File(cache, "updates").apply { mkdir() }, "update-123.apk").apply { writeText("keep") }
        UpdateCheckerManager.cleanupInstalledApks(cache, packageName, "custom-build", { error("must not parse") })
        assertTrue(ready.exists())
    }

    @Test
    fun emptyCacheNeedsNoArchiveWork() {
        UpdateCheckerManager.cleanupInstalledApks(cache, packageName, "2.3.15.4", { error("must not parse") })
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun broadcastBudgetStopsFurtherArchiveWork() {
        val updates = File(cache, "updates").apply { mkdir() }
        listOf("update-1.apk", "update-2.apk").forEach { File(updates, it).writeText("synthetic") }
        var inspected = 0
        UpdateCheckerManager.cleanupInstalledApks(cache, packageName, "2.3.15.4",
            readArchive = { inspected++; archive("2.3.15.3") },
            hasTime = { inspected == 0 })
        assertEquals(1, inspected)
        assertEquals(1, updates.listFiles().orEmpty().size)
    }

    @Test
    fun prereleaseInstallKeepsTheFutureStableArchive() {
        val updates = File(cache, "updates").apply { mkdir() }
        val stable = File(updates, "update-stable.apk").apply { writeText("future stable") }
        val older = File(updates, "update-older.apk").apply { writeText("older preview") }
        UpdateCheckerManager.cleanupInstalledApks(cache, packageName, "2.3.15.4-testpre.10", { file ->
            archive(if (file == stable) "2.3.15.4" else "2.3.15.4-testpre.9")
        })
        assertTrue(stable.exists())
        assertTrue(!older.exists())
    }

    private fun archive(version: String?, appPackage: String = packageName): PackageInfo =
        mock<PackageInfo>().apply {
            packageName = appPackage
            versionName = version
        }
}
