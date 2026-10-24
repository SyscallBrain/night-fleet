package pt.tiago.nightfleet.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.PatrolDefaults

class PatrolCatalogTest {

    @Test
    fun withoutAnyContentThePatrolUsesTheBuiltInConfig() {
        val repository = CatalogRepository { null }
        assertEquals(CatalogResult.Ok(PatrolDefaults.config()), repository.patrol())
    }

    @Test
    fun aListedPatrolFileThatFailsItsHashIsRefused() {
        val manifest = """{"format":"nightfleet-assets-1","contentVersion":1,"engineVersion":1,"aiVersion":1,""" +
            """"files":{"patrol/config.json":"0000000000000000000000000000000000000000000000000000000000000000"}}"""
        val repository = CatalogRepository { path ->
            when (path) {
                "manifest.json" -> manifest.toByteArray()
                "patrol/config.json" -> "{}".toByteArray()
                else -> null
            }
        }
        assertTrue(repository.patrol() is CatalogResult.Failed)
    }
}
