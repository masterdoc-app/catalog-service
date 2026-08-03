package pro.masterdoc.catalog

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun createsActiveAssetWithUrlSafeQrToken() {
        val orgId = "org-qr-active"

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            val site = JdbcSiteStore(ds).create(orgId, CreateSiteRequest(name = "Цех"))
            val store = JdbcAssetStore(ds)
            val asset = store.create(
                orgId,
                CreateAssetRequest(name = "Компрессор", siteId = site.id, asDraft = false),
            )

            val token = assertNotNull(asset.qrToken)
            assertTrue(token.length >= 22)
            assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")))
            assertEquals(asset, store.findActiveByQrToken(orgId, token))
            assertNull(store.findActiveByQrToken("another-org", token))
        }
    }

    @Test
    fun confirmMintsQrTokenForDraftAsset() {
        val orgId = "org-qr-draft"

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            val site = JdbcSiteStore(ds).create(orgId, CreateSiteRequest(name = "Цех"))
            val store = JdbcAssetStore(ds)
            val asset = store.create(
                orgId,
                CreateAssetRequest(name = "Черновик", siteId = site.id),
            )

            assertNull(asset.qrToken)
            val confirmed = store.confirm(orgId, asset.id)
            assertNotNull(confirmed.qrToken)
            assertEquals(RecordStatus.active, confirmed.status)
        }
    }

    @Test
    fun ensureQrTokenReturnsExistingTokenWithoutRotation() {
        val orgId = "org-qr-ensure"

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            val site = JdbcSiteStore(ds).create(orgId, CreateSiteRequest(name = "Цех"))
            val store = JdbcAssetStore(ds)
            val asset = store.create(
                orgId,
                CreateAssetRequest(name = "Компрессор", siteId = site.id, asDraft = false),
            )

            val originalToken = assertNotNull(asset.qrToken)
            assertEquals(originalToken, store.ensureQrToken(orgId, asset.id).qrToken)
            assertEquals(originalToken, store.ensureQrToken(orgId, asset.id).qrToken)
        }
    }

    @Test
    fun ensureQrTokenMintsMissingTokenForActiveAsset() {
        val orgId = "org-qr-lazy-ensure"

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            val site = JdbcSiteStore(ds).create(orgId, CreateSiteRequest(name = "Цех"))
            val store = JdbcAssetStore(ds)
            val asset = store.create(
                orgId,
                CreateAssetRequest(name = "Компрессор", siteId = site.id, asDraft = false),
            )
            ds.connection.use { connection ->
                connection.prepareStatement("UPDATE assets SET qr_token = NULL WHERE org_id = ? AND id = ?").use { statement ->
                    statement.setString(1, orgId)
                    statement.setString(2, asset.id)
                    statement.executeUpdate()
                }
            }

            val ensured = store.ensureQrToken(orgId, asset.id)

            assertNotNull(ensured.qrToken)
            assertEquals(ensured, store.get(orgId, asset.id))
        }
    }

    @Test
    fun ensureQrTokenRejectsDraftAsset() {
        val orgId = "org-qr-draft-ensure"

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            val site = JdbcSiteStore(ds).create(orgId, CreateSiteRequest(name = "Цех"))
            val store = JdbcAssetStore(ds)
            val asset = store.create(
                orgId,
                CreateAssetRequest(name = "Черновик", siteId = site.id),
            )

            assertFailsWith<IllegalArgumentException> {
                store.ensureQrToken(orgId, asset.id)
            }
            assertNull(store.get(orgId, asset.id).qrToken)
        }
    }

    @Test
    fun findsOnlyActiveAssetsByQrToken() {
        val orgId = "org-qr-status"

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            val site = JdbcSiteStore(ds).create(orgId, CreateSiteRequest(name = "Цех"))
            val store = JdbcAssetStore(ds)
            val asset = store.create(
                orgId,
                CreateAssetRequest(name = "Насос", siteId = site.id, asDraft = false),
            )
            val token = assertNotNull(asset.qrToken)

            ds.connection.use { connection ->
                connection.prepareStatement("UPDATE assets SET status = 'draft' WHERE org_id = ? AND id = ?").use { statement ->
                    statement.setString(1, orgId)
                    statement.setString(2, asset.id)
                    statement.executeUpdate()
                }
            }

            assertNull(store.findActiveByQrToken(orgId, token))
        }
    }

    @Test
    fun enforcesQrTokenUniquenessWithinOrganization() {
        val orgId = "org-qr-unique"

        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { ds ->
            val site = JdbcSiteStore(ds).create(orgId, CreateSiteRequest(name = "Цех"))
            val otherSite = JdbcSiteStore(ds).create("other-org", CreateSiteRequest(name = "Цех"))
            val store = JdbcAssetStore(ds)
            val first = store.create(
                orgId,
                CreateAssetRequest(name = "Насос 1", siteId = site.id, asDraft = false),
            )
            val second = store.create(
                orgId,
                CreateAssetRequest(name = "Насос 2", siteId = site.id, asDraft = false),
            )
            val otherOrgAsset = store.create(
                "other-org",
                CreateAssetRequest(name = "Насос 3", siteId = otherSite.id, asDraft = false),
            )
            val token = assertNotNull(first.qrToken)

            ds.connection.use { connection ->
                connection.prepareStatement("UPDATE assets SET qr_token = ? WHERE org_id = ? AND id = ?").use { statement ->
                    statement.setString(1, token)
                    statement.setString(2, orgId)
                    statement.setString(3, second.id)
                    assertFailsWith<SQLException> { statement.executeUpdate() }
                }
            }
            ds.connection.use { connection ->
                connection.prepareStatement("UPDATE assets SET qr_token = ? WHERE org_id = ? AND id = ?").use { statement ->
                    statement.setString(1, token)
                    statement.setString(2, "other-org")
                    statement.setString(3, otherOrgAsset.id)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }
    }
}
