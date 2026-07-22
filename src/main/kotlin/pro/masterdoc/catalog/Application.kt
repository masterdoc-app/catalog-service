package pro.masterdoc.catalog

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8091
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(AssetStore()) }.start(wait = true)
}

fun Application.module(store: AssetStore) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respondText(cause.message ?: "Bad Request", status = HttpStatusCode.BadRequest)
        }
        exception<NoSuchElementException> { call, cause ->
            call.respondText(cause.message ?: "Not Found", status = HttpStatusCode.NotFound)
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        post("/assets") {
            val orgId = call.orgId()
            val req = call.receive<CreateAssetRequest>()
            val asset = store.create(orgId, req)
            call.respond(HttpStatusCode.Created, asset)
        }
        get("/assets") {
            val orgId = call.orgId()
            call.respond(AssetList(items = store.list(orgId)))
        }
        get("/assets/{id}") {
            val orgId = call.orgId()
            val id = call.parameters["id"]!!
            call.respond(store.get(orgId, id))
        }
        post("/assets/{id}/confirm") {
            val orgId = call.orgId()
            val id = call.parameters["id"]!!
            call.respond(store.confirm(orgId, id))
        }
        post("/assets/{id}/reject") {
            val orgId = call.orgId()
            val id = call.parameters["id"]!!
            store.reject(orgId, id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.orgId(): String =
    request.header("X-Org-Id")?.takeIf { it.isNotBlank() } ?: "default-org"

@Serializable
enum class RecordStatus { draft, active }

@Serializable
enum class RecordSource { manual, ai_generated }

@Serializable
data class Asset(
    val id: String,
    val orgId: String,
    val siteId: String,
    val name: String,
    val inventoryNo: String? = null,
    val category: String? = null,
    val description: String? = null,
    val status: RecordStatus,
    val source: RecordSource,
    val documentIds: List<String> = emptyList(),
)

@Serializable
data class CreateAssetRequest(
    val name: String,
    val siteId: String,
    val inventoryNo: String? = null,
    val category: String? = null,
    val description: String? = null,
    val documentIds: List<String> = emptyList(),
    val source: RecordSource = RecordSource.manual,
    val asDraft: Boolean = true,
)

@Serializable
data class AssetList(val items: List<Asset>)

class AssetStore {
    private val byId = ConcurrentHashMap<String, Asset>()

    fun create(orgId: String, req: CreateAssetRequest): Asset {
        require(req.name.isNotBlank()) { "name required" }
        require(req.siteId.isNotBlank()) { "siteId required" }
        val status = if (req.asDraft || req.source == RecordSource.ai_generated) RecordStatus.draft else RecordStatus.active
        val forcedSource = if (req.source == RecordSource.ai_generated) RecordSource.ai_generated else req.source
        val asset =
            Asset(
                id = UUID.randomUUID().toString(),
                orgId = orgId,
                siteId = req.siteId,
                name = req.name.trim(),
                inventoryNo = req.inventoryNo?.trim()?.takeIf { it.isNotEmpty() },
                category = req.category?.trim()?.takeIf { it.isNotEmpty() },
                description = req.description?.trim()?.takeIf { it.isNotEmpty() },
                status = if (forcedSource == RecordSource.ai_generated) RecordStatus.draft else status,
                source = forcedSource,
                documentIds = req.documentIds,
            )
        byId[asset.id] = asset
        return asset
    }

    fun list(orgId: String): List<Asset> = byId.values.filter { it.orgId == orgId }.sortedBy { it.name }

    fun get(orgId: String, id: String): Asset {
        val asset = byId[id] ?: throw NoSuchElementException("Asset not found")
        if (asset.orgId != orgId) throw NoSuchElementException("Asset not found")
        return asset
    }

    fun confirm(orgId: String, id: String): Asset {
        val asset = get(orgId, id)
        if (asset.status != RecordStatus.draft) throw IllegalArgumentException("Only draft assets can be confirmed")
        val published = asset.copy(status = RecordStatus.active)
        byId[id] = published
        return published
    }

    fun reject(orgId: String, id: String) {
        val asset = get(orgId, id)
        if (asset.status != RecordStatus.draft) throw IllegalArgumentException("Only draft assets can be rejected")
        byId.remove(id)
    }

    fun exists(orgId: String, id: String): Boolean =
        runCatching { get(orgId, id) }.isSuccess
}
