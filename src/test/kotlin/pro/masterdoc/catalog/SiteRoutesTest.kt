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
import kotlinx.serialization.json.jsonArray
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
    fun emptyOrgListSeedsDefaultCeh1() = testApplication {
        application { module() }
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
    fun secondListDoesNotDuplicateDefault() = testApplication {
        application { module() }
        repeat(2) {
            client.get("/sites") { header("X-Org-Id", "org-once") }
        }
        val list = client.get("/sites") { header("X-Org-Id", "org-once") }
        val items = json.parseToJsonElement(list.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("ceh-1", items[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun existingSiteSkipsSeed() = testApplication {
        application { module() }
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
    fun afterDeleteAllListReseedsDefault() = testApplication {
        application { module() }
        client.get("/sites") { header("X-Org-Id", "org-reseed") }
        client.delete("/sites/ceh-1") { header("X-Org-Id", "org-reseed") }
        val list = client.get("/sites") { header("X-Org-Id", "org-reseed") }
        val items = json.parseToJsonElement(list.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("ceh-1", items[0].jsonObject["id"]!!.jsonPrimitive.content)
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
