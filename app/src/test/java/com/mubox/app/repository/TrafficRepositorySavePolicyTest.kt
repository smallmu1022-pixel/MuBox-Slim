package com.mubox.app.repository

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficRepositorySavePolicyTest {

    @Test
    fun repeatedRequestsShareOneDelayedSave() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saveCount = AtomicInteger(0)
        val scheduler = MergedTrafficSaveScheduler(scope, delayMs = 40L) {
            saveCount.incrementAndGet()
            true
        }

        repeat(5) { scheduler.requestSave() }
        delay(180L)

        assertEquals(1, saveCount.get())
        scheduler.cancel()
        scope.cancel()
    }

    @Test
    fun flushPersistsImmediatelyWithoutDelayedDuplicate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saveCount = AtomicInteger(0)
        val scheduler = MergedTrafficSaveScheduler(scope, delayMs = 200L) {
            saveCount.incrementAndGet()
            true
        }

        scheduler.requestSave()
        assertTrue(scheduler.flush())
        delay(300L)

        assertEquals(1, saveCount.get())
        scheduler.cancel()
        scope.cancel()
    }

    @Test
    fun cancelWaitsForInFlightSaveBeforeReloadOrClearContinues() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saveStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val cancelCompleted = AtomicBoolean(false)
        val scheduler = MergedTrafficSaveScheduler(scope, delayMs = 0L) {
            saveStarted.countDown()
            releaseSave.await(5, TimeUnit.SECONDS)
            true
        }

        scheduler.requestSave()
        assertTrue(saveStarted.await(5, TimeUnit.SECONDS))
        val cancelJob = async(Dispatchers.Default) {
            scheduler.cancel()
            cancelCompleted.set(true)
        }

        assertFalse(cancelCompleted.get())
        releaseSave.countDown()
        cancelJob.await()

        assertTrue(cancelCompleted.get())
        scope.cancel()
    }

    @Test
    fun flushSnapshotOlderThanClearCutoffCannotCommit() {
        assertFalse(
            canCommitTrafficSnapshot(
                snapshotGeneration = 41L,
                clearCutoffGeneration = 42L
            )
        )
    }

    @Test
    fun flushSnapshotAtClearCutoffCanCommit() {
        assertTrue(
            canCommitTrafficSnapshot(
                snapshotGeneration = 42L,
                clearCutoffGeneration = 42L
            )
        )
    }

    @Test
    fun staleSnapshotWrittenAfterClearIsIgnored() = withTempDirectory { directory ->
        val store = TrafficSnapshotFileStore(directory)
        val staleSnapshot = snapshot(generation = 10L, upload = 100L, download = 200L)
        val clearedSnapshot = emptySnapshot(generation = 11L)

        assertTrue(store.save(staleSnapshot, clearCutoffGeneration = 10L))
        assertTrue(store.save(clearedSnapshot, clearCutoffGeneration = 11L))

        val snapshotFile = directory.listFiles().orEmpty().single { it.extension == "json" }
        snapshotFile.writeText(Gson().toJson(staleSnapshot), Charsets.UTF_8)

        assertNull(store.load(clearCutoffGeneration = 11L))
    }

    @Test
    fun snapshotAtClearGenerationPersistsMonthlyAndDailyTogether() = withTempDirectory { directory ->
        val store = TrafficSnapshotFileStore(directory)
        val snapshot = snapshot(generation = 23L, upload = 321L, download = 654L)

        assertTrue(store.save(snapshot, clearCutoffGeneration = 23L))

        val loaded = requireNotNull(store.load(clearCutoffGeneration = 23L))
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })
        assertEquals(23L, loaded.generation)
        assertEquals(321L, loaded.traffic.getValue(NODE_ID).upload)
        assertEquals(654L, loaded.daily.getValue(DATE_KEY).nodeStats.getValue(NODE_ID).download)
        assertEquals(321L, loaded.allTimeTraffic?.getValue(NODE_ID)?.upload)
    }

    @Test
    fun legacyMonthlyAndDailyFilesMigrateToSingleSnapshot() = withTempDirectory { directory ->
        val gson = Gson()
        val legacySnapshot = snapshot(generation = 0L, upload = 77L, download = 88L)
        val legacyStatsFile = File(directory, "traffic_stats.json")
        val legacyDailyFile = File(directory, "traffic_daily.json")
        legacyStatsFile.writeText(gson.toJson(legacySnapshot.traffic), Charsets.UTF_8)
        legacyDailyFile.writeText(gson.toJson(legacySnapshot.daily), Charsets.UTF_8)

        val loaded = requireNotNull(
            TrafficSnapshotFileStore(directory).load(clearCutoffGeneration = 31L)
        )

        assertEquals(31L, loaded.generation)
        assertEquals(77L, loaded.traffic.getValue(NODE_ID).upload)
        assertEquals(88L, loaded.daily.getValue(DATE_KEY).nodeStats.getValue(NODE_ID).download)
        assertEquals(77L, loaded.allTimeTraffic?.getValue(NODE_ID)?.upload)
        assertFalse(legacyStatsFile.exists())
        assertFalse(legacyDailyFile.exists())
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test
    fun legacySnapshotSeedsAllTimeWithoutDoubleCountingMonthlyDailyOverlap() = withTempDirectory { directory ->
        val store = TrafficSnapshotFileStore(directory)
        val legacySnapshot = snapshot(generation = 24L, upload = 77L, download = 88L)
        val previousMonthStats = NodeTrafficStats(
            nodeId = NODE_ID,
            upload = 11L,
            download = 13L,
            lastUpdated = 23L,
            nodeName = "Test Node"
        )
        val withRetainedHistory = legacySnapshot.copy(
            monthKey = "2026-7",
            daily = mapOf(
                "2026-6-30" to DailyTrafficRecord("2026-6-30").apply {
                    nodeStats[NODE_ID] = previousMonthStats
                },
                DATE_KEY to legacySnapshot.daily.getValue(DATE_KEY)
            )
        )

        assertTrue(store.save(withRetainedHistory, clearCutoffGeneration = 24L))

        val migrated = requireNotNull(store.load(clearCutoffGeneration = 24L))
        val reloaded = requireNotNull(store.load(clearCutoffGeneration = 24L))
        assertEquals(88L, migrated.allTimeTraffic?.getValue(NODE_ID)?.upload)
        assertEquals(101L, migrated.allTimeTraffic?.getValue(NODE_ID)?.download)
        assertEquals(migrated.allTimeTraffic, reloaded.allTimeTraffic)
    }

    @Test
    fun legacySnapshotWithoutMonthKeyUsesMonthlyTimestampInsteadOfLatestDailyMonth() = withTempDirectory { directory ->
        val store = TrafficSnapshotFileStore(directory)
        val monthlyTimestamp = java.util.Calendar.getInstance().apply {
            clear()
            set(2026, java.util.Calendar.JUNE, 15, 12, 0, 0)
        }.timeInMillis
        val monthlyStats = NodeTrafficStats(
            nodeId = NODE_ID,
            upload = 100L,
            download = 200L,
            lastUpdated = monthlyTimestamp,
            nodeName = "Test Node"
        )
        val juneStats = monthlyStats.copy(upload = 20L, download = 40L)
        val julyStats = monthlyStats.copy(upload = 5L, download = 7L)
        val unparseableMonthlyStats = monthlyStats.copy(upload = 999L, download = 888L)
        val unknownDateStats = NodeTrafficStats("node-b", upload = 9L, download = 10L)
        val snapshotWithoutMonthKey = TrafficPersistenceSnapshot(
            generation = 26L,
            traffic = mapOf(NODE_ID to monthlyStats),
            daily = mapOf(
                "2026-6-30" to DailyTrafficRecord("2026-6-30").apply {
                    nodeStats[NODE_ID] = juneStats
                },
                "2026-7-1" to DailyTrafficRecord("2026-7-1").apply {
                    nodeStats[NODE_ID] = julyStats
                },
                "not-a-date" to DailyTrafficRecord("not-a-date").apply {
                    nodeStats[NODE_ID] = unparseableMonthlyStats
                },
                "invalid-date" to DailyTrafficRecord("invalid-date").apply {
                    nodeStats["node-b"] = unknownDateStats
                }
            )
        )
        assertTrue(store.save(snapshotWithoutMonthKey, clearCutoffGeneration = 26L))

        val migrated = requireNotNull(store.load(clearCutoffGeneration = 26L))
        assertEquals(105L, migrated.allTimeTraffic?.getValue(NODE_ID)?.upload)
        assertEquals(207L, migrated.allTimeTraffic?.getValue(NODE_ID)?.download)
        assertEquals(9L, migrated.allTimeTraffic?.getValue("node-b")?.upload)
    }

    @Test
    fun allTimeLedgerSurvivesDailyRetentionAndMonthReset() = withTempDirectory { directory ->
        val store = TrafficSnapshotFileStore(directory)
        val oldMonthly = NodeTrafficStats(NODE_ID, upload = 120L, download = 240L)
        val lifetime = NodeTrafficStats(NODE_ID, upload = 900L, download = 1_800L)
        val initial = TrafficPersistenceSnapshot(
            generation = 25L,
            traffic = mapOf(NODE_ID to oldMonthly),
            daily = mapOf(DATE_KEY to DailyTrafficRecord(DATE_KEY).apply {
                nodeStats[NODE_ID] = oldMonthly.copy()
            }),
            monthKey = "2026-7",
            allTimeTraffic = mapOf(NODE_ID to lifetime)
        )
        assertTrue(store.save(initial, clearCutoffGeneration = 25L))

        val rolledOver = requireNotNull(store.load(clearCutoffGeneration = 25L) { snapshot ->
            snapshot.copy(traffic = emptyMap(), daily = emptyMap(), monthKey = "2026-8")
        })
        assertTrue(rolledOver.traffic.isEmpty())
        assertTrue(rolledOver.daily.isEmpty())
        assertEquals(900L, rolledOver.allTimeTraffic?.getValue(NODE_ID)?.upload)

        val newMonthDelta = NodeTrafficStats(NODE_ID, upload = 30L, download = 60L)
        val merged = requireNotNull(store.mergeDeltas(
            deltas = TrafficPersistenceSnapshot(
                generation = 25L,
                traffic = mapOf(NODE_ID to newMonthDelta),
                daily = emptyMap(),
                monthKey = "2026-8",
                allTimeTraffic = mapOf(NODE_ID to newMonthDelta)
            ),
            clearCutoffGeneration = 25L
        ))
        assertEquals(30L, merged.traffic.getValue(NODE_ID).upload)
        assertEquals(930L, merged.allTimeTraffic?.getValue(NODE_ID)?.upload)
    }

    @Test
    fun loadMaintenanceTransformsLatestSnapshotInsideFileLock() = withTempDirectory { directory ->
        val store = TrafficSnapshotFileStore(directory)
        val oldMonth = snapshot(generation = 9L, upload = 12L, download = 34L)

        assertTrue(store.save(oldMonth, clearCutoffGeneration = 9L))

        val loaded = requireNotNull(store.load(clearCutoffGeneration = 9L) { current ->
            current.copy(traffic = emptyMap(), monthKey = "2026-7")
        })
        val reloaded = requireNotNull(store.load(clearCutoffGeneration = 9L))

        assertTrue(loaded.traffic.isEmpty())
        assertEquals("2026-7", reloaded.monthKey)
        assertTrue(reloaded.traffic.isEmpty())
        assertEquals(34L, reloaded.daily.getValue(DATE_KEY).nodeStats.getValue(NODE_ID).download)
    }

    private fun snapshot(generation: Long, upload: Long, download: Long): TrafficPersistenceSnapshot {
        val monthly = NodeTrafficStats(
            nodeId = NODE_ID,
            upload = upload,
            download = download,
            lastUpdated = generation,
            nodeName = "Test Node"
        )
        val dailyRecord = DailyTrafficRecord(DATE_KEY).apply {
            nodeStats[NODE_ID] = monthly.copy()
        }
        return TrafficPersistenceSnapshot(
            generation = generation,
            traffic = mapOf(NODE_ID to monthly),
            daily = mapOf(DATE_KEY to dailyRecord)
        )
    }

    private fun emptySnapshot(generation: Long): TrafficPersistenceSnapshot {
        return TrafficPersistenceSnapshot(
            generation = generation,
            traffic = emptyMap(),
            daily = emptyMap(),
            allTimeTraffic = emptyMap()
        )
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("traffic_snapshot_").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val NODE_ID = "node-a"
        const val DATE_KEY = "2026-7-11"
    }
}
