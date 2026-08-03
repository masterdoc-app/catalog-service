package pro.masterdoc.catalog

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
class SiteRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dataSource: HikariDataSource

    @Test
    fun createListUpdateDelete() = withApplication {
        val create =
            client.post("/sites") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"id":"цех-1","name":"Цех 1","address":"ул. Заводская 1"}""")
            }
        assertEquals(HttpStatusCode.Created, create.status)
        val created = json.parseToJsonElement(create.bodyAsText()).jsonObject
        assertEquals("цех-1", created["id"]!!.jsonPrimitive.content)
        assertEquals("Цех 1", created["name"]!!.jsonPrimitive.content)

        val list = client.get("/sites") { header("X-Org-Id", "org-1") }
        assertTrue(list.bodyAsText().contains("Цех 1"))

        val patch =
            client.patch("/sites/цех-1") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Цех №1","address":""}""")
            }
        assertEquals(HttpStatusCode.OK, patch.status)
        val updated = json.parseToJsonElement(patch.bodyAsText()).jsonObject
        assertEquals("Цех №1", updated["name"]!!.jsonPrimitive.content)
        assertTrue(updated["address"] == null || updated["address"]!!.toString() == "null")

        val delete = client.delete("/sites/цех-1") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NoContent, delete.status)
        val get = client.get("/sites/цех-1") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun createSiteWithGeofenceReturnsItOnGet() = withApplication {
        val create =
            client.post("/sites") {
                header("X-Org-Id", "org-geofence")
                contentType(ContentType.Application.Json)
                setBody("""{"id":"site-geofence","name":"Geofenced","lat":55.75,"lon":37.61,"geofenceRadiusM":250}""")
            }
        assertEquals(HttpStatusCode.Created, create.status)

        val get = client.get("/sites/site-geofence") { header("X-Org-Id", "org-geofence") }
        assertEquals(HttpStatusCode.OK, get.status)
        val site = json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals("55.75", site["lat"]!!.jsonPrimitive.content)
        assertEquals("37.61", site["lon"]!!.jsonPrimitive.content)
        assertEquals("250", site["geofenceRadiusM"]!!.jsonPrimitive.content)
    }

    @Test
    fun invalidGeofenceValuesReturnBadRequest() = withApplication {
        val invalidRequests =
            listOf(
                """{"id":"invalid-lat","name":"Invalid lat","lat":90.1}""",
                """{"id":"invalid-lon","name":"Invalid lon","lon":180.1}""",
                """{"id":"invalid-radius","name":"Invalid radius","geofenceRadiusM":0}""",
            )

        invalidRequests.forEach { body ->
            val response =
                client.post("/sites") {
                    header("X-Org-Id", "org-invalid-geofence")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun updateSiteChangesGeofenceFields() = withApplication {
        client.post("/sites") {
            header("X-Org-Id", "org-update-geofence")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"site-update","name":"Site","lat":55.0,"lon":37.0,"geofenceRadiusM":100}""")
        }

        val update =
            client.patch("/sites/site-update") {
                header("X-Org-Id", "org-update-geofence")
                contentType(ContentType.Application.Json)
                setBody("""{"lat":56.0,"lon":38.0,"geofenceRadiusM":300}""")
            }
        assertEquals(HttpStatusCode.OK, update.status)
        val site = json.parseToJsonElement(update.bodyAsText()).jsonObject
        assertEquals("56.0", site["lat"]!!.jsonPrimitive.content)
        assertEquals("38.0", site["lon"]!!.jsonPrimitive.content)
        assertEquals("300", site["geofenceRadiusM"]!!.jsonPrimitive.content)
    }

    @Test
    fun deleteBlockedWhenAssetsPresent() = withApplication {
        client.post("/sites") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"s1","name":"S1"}""")
        }
        client.post("/assets") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"A","siteId":"s1","documentIds":["doc-site-delete-blocked"]}""")
        }
        val delete = client.delete("/sites/s1") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.Conflict, delete.status)
    }

    @Test
    fun emptyOrgListSeedsDefaultCeh1() = withApplication {
        val list = client.get("/sites") { header("X-Org-Id", "org-empty") }
        assertEquals(HttpStatusCode.OK, list.status)
        val items = json.parseToJsonElement(list.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        val site = items[0].jsonObject
        assertEquals("ceh-1", site["id"]!!.jsonPrimitive.content)
        assertEquals("Цех 1", site["name"]!!.jsonPrimitive.content)
        assertEquals("org-empty", site["orgId"]!!.jsonPrimitive.content)
    }

    @Test
    fun secondListDoesNotDuplicateDefault() = withApplication {
        repeat(2) {
            client.get("/sites") { header("X-Org-Id", "org-once") }
        }
        val list = client.get("/sites") { header("X-Org-Id", "org-once") }
        val items = json.parseToJsonElement(list.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("ceh-1", items[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun existingSiteSkipsSeed() = withApplication {
        client.post("/sites") {
            header("X-Org-Id", "org-has")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"s-custom","name":"Свой цех"}""")
        }
        val list = client.get("/sites") { header("X-Org-Id", "org-has") }
        val items = json.parseToJsonElement(list.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("s-custom", items[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(!list.bodyAsText().contains("ceh-1"))
    }

    @Test
    fun afterDeleteAllListReseedsDefault() = withApplication {
        client.get("/sites") { header("X-Org-Id", "org-reseed") }
        client.delete("/sites/ceh-1") { header("X-Org-Id", "org-reseed") }
        val list = client.get("/sites") { header("X-Org-Id", "org-reseed") }
        val items = json.parseToJsonElement(list.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("ceh-1", items[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun sitesScopedByOrg() = withApplication {
        client.post("/sites") {
            header("X-Org-Id", "org-a")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"A"}""")
        }
        client.post("/sites") {
            header("X-Org-Id", "org-b")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"B"}""")
        }
        val list = client.get("/sites") { header("X-Org-Id", "org-a") }
        assertTrue(list.bodyAsText().contains("\"A\""))
        assertTrue(!list.bodyAsText().contains("\"B\""))
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
