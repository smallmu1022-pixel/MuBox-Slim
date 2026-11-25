package com.mubox.app.database

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDatabaseMigrationPolicyTest {

    @Test
    fun versionEightDropsUnusedNodesTableAndVersionNineAddsUnknownLatencyMethod() {
        val databaseSource = File("src/main/java/com/mubox/app/database/AppDatabase.kt").readText()
        val nodeDaoFile = File("src/main/java/com/mubox/app/database/dao/NodeDao.kt")
        val nodeEntityFile = File("src/main/java/com/mubox/app/database/entity/NodeEntity.kt")

        assertTrue(databaseSource.contains("version = 9"))
        assertTrue(databaseSource.contains("MIGRATION_7_8"))
        assertTrue(databaseSource.contains("MIGRATION_8_9"))
        assertTrue(databaseSource.contains("DROP TABLE IF EXISTS nodes"))
        assertTrue(databaseSource.contains("DROP INDEX IF EXISTS index_node_latencies_nodeId"))
        assertTrue(databaseSource.contains("ALTER TABLE node_latencies ADD COLUMN testMethod TEXT"))
        assertFalse(databaseSource.contains("NodeEntity::class"))
        assertFalse(databaseSource.contains("abstract fun nodeDao()"))
        assertFalse(nodeDaoFile.exists())
        assertFalse(nodeEntityFile.exists())
    }
}
