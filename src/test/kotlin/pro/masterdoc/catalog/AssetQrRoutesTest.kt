package pro.masterdoc.catalog

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class AssetQrRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dataSource: HikariDataSource

    @Test
    fun getQrPdfReturnsPdfForActiveAsset() = withApplication {
        client.ensureSite("org-1", "site-1", "Цех 1")
        val asset = client.createAsset("org-1", "site-1", "Компрессор", active = true)

        val response = client.get("/assets/${asset.id}/qr.pdf") {
            header("X-Org-Id", "org-1")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Pdf, response.contentType()?.withoutParameters())
        assertTrue(response.bodyAsBytes().copyOfRange(0, 5).contentEquals("%PDF-".encodeToByteArray()))
    }

    @Test
    fun getQrPdfRejectsDraftAsset() = withApplication {
        client.ensureSite("org-1", "site-1")
        val asset = client.createAsset("org-1", "site-1", "Черновик", active = false)

        val response = client.get("/assets/${asset.id}/qr.pdf") {
            header("X-Org-Id", "org-1")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun getByQrReturnsAssetAndSiteNames() = withApplication {
        client.ensureSite("org-1", "site-1", "Цех 1")
        val asset = client.createAsset("org-1", "site-1", "Компрессор", active = true)

        val response = client.get("/assets/by-qr/${asset.qrToken}") {
            header("X-Org-Id", "org-1")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            AssetQrResolveResponse(
                assetId = asset.id,
                name = "Компрессор",
                siteId = "site-1",
                siteName = "Цех 1",
            ),
            json.decodeFromString(response.bodyAsText()),
        )
    }

    @Test
    fun getByQrReturnsNotFoundForUnknownOtherOrgOrInactiveToken() = withApplication {
        client.ensureSite("org-1", "site-1")
        val asset = client.createAsset("org-1", "site-1", active = true)

        val unknown = client.get("/assets/by-qr/unknown") {
            header("X-Org-Id", "org-1")
        }
        val otherOrg = client.get("/assets/by-qr/${asset.qrToken}") {
            header("X-Org-Id", "org-2")
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE assets SET status = 'draft' WHERE org_id = ? AND id = ?").use { statement ->
                statement.setString(1, "org-1")
                statement.setString(2, asset.id)
                statement.executeUpdate()
            }
        }
        val inactive = client.get("/assets/by-qr/${asset.qrToken}") {
            header("X-Org-Id", "org-1")
        }

        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals(HttpStatusCode.NotFound, otherOrg.status)
        assertEquals(HttpStatusCode.NotFound, inactive.status)
    }

    @Test
    fun getByQrHonorsScopeFilterWithoutLeakingAsset() = withApplication {
        client.ensureSite("org-1", "site-1")
        val asset = client.createAsset("org-1", "site-1", active = true)

        val denied = client.get("/assets/by-qr/${asset.qrToken}") {
            header("X-Org-Id", "org-1")
            header("X-User-Id", "engineer-1")
            header("X-Scope-Filter", "1")
        }
        assertEquals(HttpStatusCode.NotFound, denied.status)

        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":[],"assetIds":["${asset.id}"]}""")
        }
        val allowed = client.get("/assets/by-qr/${asset.qrToken}") {
            header("X-Org-Id", "org-1")
            header("X-User-Id", "engineer-1")
            header("X-Scope-Filter", "true")
        }

        assertEquals(HttpStatusCode.OK, allowed.status)
    }

    private suspend fun io.ktor.client.HttpClient.ensureSite(
        orgId: String,
        siteId: String,
        name: String = "Site",
    ) {
        post("/sites") {
            header("X-Org-Id", orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$siteId","name":"$name"}""")
        }
    }

    private suspend fun io.ktor.client.HttpClient.createAsset(
        orgId: String,
        siteId: String,
        name: String = "Asset",
        active: Boolean,
    ): Asset {
        val response = post("/assets") {
            header("X-Org-Id", orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","siteId":"$siteId","asDraft":${!active}}""")
        }
        return json.decodeFromString(response.bodyAsText())
    }

    private fun withApplication(block: suspend ApplicationTestBuilder.() -> Unit) {
        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { connected ->
            dataSource = connected
            connected.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("TRUNCATE user_scopes, assets, sites")
                }
            }
            testApplication {
                application { module(dataSource) }
                block()
            }
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog")
    }
}
