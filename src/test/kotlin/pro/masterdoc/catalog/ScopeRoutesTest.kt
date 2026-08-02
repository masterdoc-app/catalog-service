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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Testcontainers(disabledWithoutDocker = true)
class ScopeRoutesTest {
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

    @Test
    fun putGetScopeRoundTrip() = withApplication {
        val put =
            client.put("/user-scopes/user-1") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"siteIds":["s1","s2"],"assetIds":["a1"]}""")
            }
        assertEquals(HttpStatusCode.OK, put.status)
        val putBody = json.parseToJsonElement(put.bodyAsText()).jsonObject
        assertEquals("user-1", putBody["userId"]!!.jsonPrimitive.content)
        assertEquals("[\"s1\",\"s2\"]", putBody["siteIds"].toString())
        assertEquals("[\"a1\"]", putBody["assetIds"].toString())

        val get = client.get("/user-scopes/user-1") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.OK, get.status)
        val getBody = json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals("[\"s1\",\"s2\"]", getBody["siteIds"].toString())
        assertEquals("[\"a1\"]", getBody["assetIds"].toString())
    }

    @Test
    fun getEmptyScopeReturnsDefaults() = withApplication {
        val get = client.get("/user-scopes/unknown-user") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.OK, get.status)
        val body = json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals("unknown-user", body["userId"]!!.jsonPrimitive.content)
        assertEquals("[]", body["siteIds"].toString())
        assertEquals("[]", body["assetIds"].toString())
    }

    @Test
    fun scopesScopedByOrg() = withApplication {
        client.put("/user-scopes/user-1") {
            header("X-Org-Id", "org-a")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["site-a"],"assetIds":[]}""")
        }
        client.put("/user-scopes/user-1") {
            header("X-Org-Id", "org-b")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["site-b"],"assetIds":[]}""")
        }

        val getA = client.get("/user-scopes/user-1") { header("X-Org-Id", "org-a") }
        assertTrue(getA.bodyAsText().contains("site-a"))
        assertTrue(!getA.bodyAsText().contains("site-b"))

        val getB = client.get("/user-scopes/user-1") { header("X-Org-Id", "org-b") }
        assertTrue(getB.bodyAsText().contains("site-b"))
        assertTrue(!getB.bodyAsText().contains("site-a"))
    }

    @Test
    fun coversTrueViaSiteMembership() = withApplication {
        client.ensureSite("org-1", "s1")
        val assetId = client.createAsset("org-1", "s1")
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1"],"assetIds":[]}""")
        }

        val covers =
            client.get("/user-scopes/engineer-1/covers/$assetId") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, covers.status)
        assertTrue(json.parseToJsonElement(covers.bodyAsText()).jsonObject["covers"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun coversTrueViaPin() = withApplication {
        client.ensureSite("org-1", "s1")
        client.ensureSite("org-1", "s2")
        val assetId = client.createAsset("org-1", "s2", "Pinned")
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1"],"assetIds":["$assetId"]}""")
        }

        val covers =
            client.get("/user-scopes/engineer-1/covers/$assetId") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, covers.status)
        assertTrue(json.parseToJsonElement(covers.bodyAsText()).jsonObject["covers"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun coversFalseWhenNeitherSiteNorPin() = withApplication {
        client.ensureSite("org-1", "s1")
        client.ensureSite("org-1", "s2")
        val assetId = client.createAsset("org-1", "s2")
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1"],"assetIds":[]}""")
        }

        val covers =
            client.get("/user-scopes/engineer-1/covers/$assetId") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, covers.status)
        assertFalse(json.parseToJsonElement(covers.bodyAsText()).jsonObject["covers"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun coversFalseForEmptyScope() = withApplication {
        client.ensureSite("org-1", "s1")
        val assetId = client.createAsset("org-1", "s1")

        val covers =
            client.get("/user-scopes/engineer-1/covers/$assetId") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, covers.status)
        assertFalse(json.parseToJsonElement(covers.bodyAsText()).jsonObject["covers"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun pinSurvivesAssetMove() = withApplication {
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

        val covers =
            client.get("/user-scopes/engineer-1/covers/$assetId") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, covers.status)
        assertTrue(json.parseToJsonElement(covers.bodyAsText()).jsonObject["covers"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun coversNotFoundForUnknownAsset() = withApplication {
        client.put("/user-scopes/engineer-1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1"],"assetIds":[]}""")
        }
        val covers =
            client.get("/user-scopes/engineer-1/covers/missing-asset") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.NotFound, covers.status)
    }

    @Test
    fun candidatesListsUsersCoveringAsset() = withApplication {
        client.ensureSite("org-1", "s1")
        val assetId = client.createAsset("org-1", "s1")
        client.put("/user-scopes/e1") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1"],"assetIds":[]}""")
        }
        client.put("/user-scopes/e2") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":[],"assetIds":[]}""")
        }
        client.put("/user-scopes/e3") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":[],"assetIds":["$assetId"]}""")
        }

        val candidates =
            client.get("/user-scopes/candidates/$assetId") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, candidates.status)
        val userIds =
            json.parseToJsonElement(candidates.bodyAsText()).jsonObject["userIds"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        assertEquals(listOf("e1", "e3"), userIds)
    }

    @Test
    fun candidatesScopedByOrg() = withApplication {
        client.ensureSite("org-a", "s1")
        client.ensureSite("org-b", "s1")
        val assetA = client.createAsset("org-a", "s1")
        val assetB = client.createAsset("org-b", "s1")
        client.put("/user-scopes/e1") {
            header("X-Org-Id", "org-a")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1"],"assetIds":[]}""")
        }
        client.put("/user-scopes/e2") {
            header("X-Org-Id", "org-b")
            contentType(ContentType.Application.Json)
            setBody("""{"siteIds":["s1"],"assetIds":[]}""")
        }

        val candidatesA =
            client.get("/user-scopes/candidates/$assetA") {
                header("X-Org-Id", "org-a")
            }
        val userIdsA =
            json.parseToJsonElement(candidatesA.bodyAsText()).jsonObject["userIds"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        assertEquals(listOf("e1"), userIdsA)

        val candidatesB =
            client.get("/user-scopes/candidates/$assetB") {
                header("X-Org-Id", "org-b")
            }
        val userIdsB =
            json.parseToJsonElement(candidatesB.bodyAsText()).jsonObject["userIds"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        assertEquals(listOf("e2"), userIdsB)
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
