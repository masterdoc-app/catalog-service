package pro.masterdoc.catalog

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.server.testing.ApplicationTestBuilder
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Testcontainers(disabledWithoutDocker = true)
class ScopeFilterAssetsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dataSource: HikariDataSource

    private suspend fun io.ktor.client.HttpClient.ensureSite(
        orgId: String,
        siteId: String,
        name: String = siteId,
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
    ): String {
        val create =
            post("/assets") {
                header("X-Org-Id", orgId)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"$name","siteId":"$siteId","asDraft":false}""")
            }
        return json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun assetNames(body: String): List<String> =
        json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun ae1EmptyScopeWithFilterReturnsEmptyList() = withApplication {
        client.ensureSite("org-1", "s1")
        client.createAsset("org-1", "s1", "VisibleToAdmin")

        val list =
            client.get("/assets") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(emptyList(), assetNames(list.bodyAsText()))
    }

    @Test
    fun ae1MissingUserIdWithFilterTreatsAsEmptyScope() = withApplication {
        client.ensureSite("org-1", "s1")
        client.createAsset("org-1", "s1")

        val list =
            client.get("/assets") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
            }
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(emptyList(), assetNames(list.bodyAsText()))
    }

    @Test
    fun ae2BoundSiteShowsAssetsAtSite() = withApplication {
        client.ensureSite("org-1", "s1")
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1"],"assetIds":[]}""")
        }
        val assetId = client.createAsset("org-1", "s1", "AtBoundSite")

        val list =
            client.get("/assets") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(HttpStatusCode.OK, list.status)
        val names = assetNames(list.bodyAsText())
        assertEquals(listOf("AtBoundSite"), names)
        assertTrue(list.bodyAsText().contains(assetId))
    }

    @Test
    fun ae3PinOnlyShowsPinnedAssetNotSiblings() = withApplication {
        client.ensureSite("org-1", "s1")
        val pinnedId = client.createAsset("org-1", "s1", "Pinned")
        client.createAsset("org-1", "s1", "Sibling")
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":[],"assetIds":["$pinnedId"]}""")
        }

        val list =
            client.get("/assets") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(listOf("Pinned"), assetNames(list.bodyAsText()))
    }

    @Test
    fun ae5PinSurvivesAssetMoveInFilteredList() = withApplication {
        client.ensureSite("org-1", "s1")
        client.ensureSite("org-1", "s2")
        val assetId = client.createAsset("org-1", "s1", "Movable")
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":[],"assetIds":["$assetId"]}""")
        }
        client.post("/assets/$assetId/move") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteId":"s2"}""")
        }

        val list =
            client.get("/assets") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(listOf("Movable"), assetNames(list.bodyAsText()))
    }

    @Test
    fun filterOffOrAbsentReturnsFullOrgList() = withApplication {
        client.ensureSite("org-1", "s1")
        client.createAsset("org-1", "s1", "A")
        client.createAsset("org-1", "s1", "B")

        val noHeader =
            client.get("/assets") {
                header("X-Org-Id", "org-1")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(2, assetNames(noHeader.bodyAsText()).size)

        val filterZero =
            client.get("/assets") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "0")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(2, assetNames(filterZero.bodyAsText()).size)
    }

    @Test
    fun scopeFilterTrueHeaderWorks() = withApplication {
        client.ensureSite("org-1", "s1")
        val assetId = client.createAsset("org-1", "s1", "Pinned")
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":[],"assetIds":["$assetId"]}""")
        }

        val list =
            client.get("/assets") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "true")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(listOf("Pinned"), assetNames(list.bodyAsText()))
    }

    @Test
    fun siteIdQueryIntersectsWithScopeFilter() = withApplication {
        client.ensureSite("org-1", "s1")
        client.ensureSite("org-1", "s2")
        client.createAsset("org-1", "s1", "AtS1")
        client.createAsset("org-1", "s2", "AtS2")
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1","s2"],"assetIds":[]}""")
        }

        val list =
            client.get("/assets?siteId=s1") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(listOf("AtS1"), assetNames(list.bodyAsText()))
    }

    @Test
    fun orgIsolationWithScopeFilter() = withApplication {
        client.ensureSite("org-a", "s1")
        client.ensureSite("org-b", "s1")
        client.createAsset("org-a", "s1", "OrgA")
        client.createAsset("org-b", "s1", "OrgB")
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-a")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1"],"assetIds":[]}""")
        }

        val list =
            client.get("/assets") {
                header("X-Org-Id", "org-a")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(listOf("OrgA"), assetNames(list.bodyAsText()))
        assertTrue(!list.bodyAsText().contains("OrgB"))
    }

    @Test
    fun getByIdUnchangedWithoutScopeFilter() = withApplication {
        client.ensureSite("org-1", "s1")
        val assetId = client.createAsset("org-1", "s1", "Direct")

        val get =
            client.get("/assets/$assetId") {
                header("X-Org-Id", "org-1")
                header("X-Scope-Filter", "1")
                header("X-User-Id", "engineer-1")
            }
        assertEquals(HttpStatusCode.OK, get.status)
        assertTrue(get.bodyAsText().contains("Direct"))
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
