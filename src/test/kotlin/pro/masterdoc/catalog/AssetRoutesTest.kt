package pro.masterdoc.catalog

import io.ktor.client.request.get
import io.ktor.client.request.header
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

class AssetRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun io.ktor.client.HttpClient.ensureSite(
        orgId: String,
        siteId: String = "site-1",
        name: String = "Site",
    ) {
        post("/sites") {
            header("X-Org-Id", orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$siteId","name":"$name"}""")
        }
    }

    @Test
    fun createDraftAndConfirm() = testApplication {
        application { module() }
        client.ensureSite("org-1")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Компрессор","siteId":"site-1","source":"ai_generated","documentIds":["doc-1"]}""",
                )
            }
        assertEquals(HttpStatusCode.Created, create.status)
        val created = json.parseToJsonElement(create.bodyAsText()).jsonObject
        assertEquals("draft", created["status"]!!.jsonPrimitive.content)
        assertEquals("ai_generated", created["source"]!!.jsonPrimitive.content)
        val id = created["id"]!!.jsonPrimitive.content

        val confirm =
            client.post("/assets/$id/confirm") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, confirm.status)
        assertEquals("active", json.parseToJsonElement(confirm.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun confirmNonDraftFails() = testApplication {
        application { module() }
        client.ensureSite("org-1", "s")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"A","siteId":"s","source":"manual","asDraft":false}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val confirm = client.post("/assets/$id/confirm") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.BadRequest, confirm.status)
    }

    @Test
    fun listScopedByOrg() = testApplication {
        application { module() }
        client.ensureSite("org-a", "s")
        client.ensureSite("org-b", "s")
        client.post("/assets") {
            header("X-Org-Id", "org-a")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"A","siteId":"s"}""")
        }
        client.post("/assets") {
            header("X-Org-Id", "org-b")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"B","siteId":"s"}""")
        }
        val list = client.get("/assets") { header("X-Org-Id", "org-a") }
        assertTrue(list.bodyAsText().contains("\"A\""))
        assertTrue(!list.bodyAsText().contains("\"B\""))
    }

    @Test
    fun rejectDraftSucceeds() = testApplication {
        application { module() }
        client.ensureSite("org-1", "s")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Draft","siteId":"s","source":"ai_generated"}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val reject = client.post("/assets/$id/reject") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NoContent, reject.status)

        val get = client.get("/assets/$id") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun rejectActiveFails() = testApplication {
        application { module() }
        client.ensureSite("org-1", "s")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Active","siteId":"s","source":"manual","asDraft":false}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val reject = client.post("/assets/$id/reject") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.BadRequest, reject.status)
    }

    @Test
    fun getOtherOrgAssetNotFound() = testApplication {
        application { module() }
        client.ensureSite("org-a", "s")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-a")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Secret","siteId":"s"}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val get = client.get("/assets/$id") { header("X-Org-Id", "org-b") }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun aiGeneratedForcesDraftEvenWhenAsDraftFalse() = testApplication {
        application { module() }
        client.ensureSite("org-1", "s")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"AI","siteId":"s","source":"ai_generated","asDraft":false}""")
            }
        assertEquals(HttpStatusCode.Created, create.status)
        assertEquals(
            "draft",
            json.parseToJsonElement(create.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun descriptionPersistedOnCreate() = testApplication {
        application { module() }
        client.ensureSite("org-1")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Кран-балка","siteId":"site-1","category":"lifting","description":"Грузоподъёмная балка","source":"ai_generated"}""",
                )
            }
        assertEquals(HttpStatusCode.Created, create.status)
        val body = json.parseToJsonElement(create.bodyAsText()).jsonObject
        assertEquals("Грузоподъёмная балка", body["description"]!!.jsonPrimitive.content)
        assertEquals("lifting", body["category"]!!.jsonPrimitive.content)
    }

    @Test
    fun createWithUnknownSiteFails() = testApplication {
        application { module() }
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"X","siteId":"missing"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, create.status)
        assertTrue(create.bodyAsText().contains("Unknown siteId"))
    }

    @Test
    fun moveAssetChangesSite() = testApplication {
        application { module() }
        client.ensureSite("org-1", "a", "A")
        client.ensureSite("org-1", "b", "B")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Pump","siteId":"a","asDraft":false}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val moved =
            client.post("/assets/$id/move") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"siteId":"b"}""")
            }
        assertEquals(HttpStatusCode.OK, moved.status)
        assertEquals("b", json.parseToJsonElement(moved.bodyAsText()).jsonObject["siteId"]!!.jsonPrimitive.content)
        val filtered = client.get("/assets?siteId=b") { header("X-Org-Id", "org-1") }
        assertTrue(filtered.bodyAsText().contains(id))
    }
}
