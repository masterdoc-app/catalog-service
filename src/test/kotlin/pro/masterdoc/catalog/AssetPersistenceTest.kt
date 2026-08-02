package pro.masterdoc.catalog

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals

@Testcontainers(disabledWithoutDocker = true)
class AssetPersistenceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog")
    }

    @Test
    fun survivesNewConnectionPool() {
        val orgId = "org-persist"
        lateinit var assetId: String

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            val site = JdbcSiteStore(ds).create(orgId, CreateSiteRequest(name = "Цех 1"))
            val asset = JdbcAssetStore(ds).create(
                orgId,
                CreateAssetRequest(name = "Компрессор", siteId = site.id),
            )
            assetId = asset.id
        }

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            val loaded = JdbcAssetStore(ds).get(orgId, assetId)
            assertEquals("Компрессор", loaded.name)
        }
    }
}
