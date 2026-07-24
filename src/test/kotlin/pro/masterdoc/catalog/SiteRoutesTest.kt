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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SiteRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun createListUpdateDelete() = testApplication {
        application { module() }
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
    fun deleteBlockedWhenAssetsPresent() = testApplication {
        application { module() }
        client.post("/sites") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"s1","name":"S1"}""")
        }
        client.post("/assets") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"A","siteId":"s1"}""")
        }
        val delete = client.delete("/sites/s1") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.Conflict, delete.status)
    }

    @Test
    fun sitesScopedByOrg() = testApplication {
        application { module() }
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
}
