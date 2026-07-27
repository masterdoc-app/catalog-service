package pro.masterdoc.catalog

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("pro.masterdoc.catalog")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8091
    log.info("event=startup port=$port")
    val sites = SiteStore()
    val assets = AssetStore()
    val scopes = ScopeStore()
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(sites, assets, scopes) }.start(wait = true)
}

fun Application.module(
    sites: SiteStore = SiteStore(),
    assets: AssetStore = AssetStore(),
    scopes: ScopeStore = ScopeStore(),
) {
    install(CallLogging) {
        level = Level.INFO
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            log.warn("event=bad_request reason=${cause.message}")
            call.respondText(cause.message ?: "Bad Request", status = HttpStatusCode.BadRequest)
        }
        exception<NoSuchElementException> { call, cause ->
            log.warn("event=not_found reason=${cause.message}")
            call.respondText(cause.message ?: "Not Found", status = HttpStatusCode.NotFound)
        }
        exception<ConflictException> { call, cause ->
            log.warn("event=conflict reason=${cause.message}")
            call.respondText(cause.message ?: "Conflict", status = HttpStatusCode.Conflict)
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        post("/sites") {
            val orgId = call.orgId()
            val req = call.receive<CreateSiteRequest>()
            call.respond(HttpStatusCode.Created, sites.create(orgId, req))
        }
        get("/sites") {
            val orgId = call.orgId()
            call.respond(SiteList(items = sites.list(orgId)))
        }
        get("/sites/{id}") {
            val orgId = call.orgId()
            call.respond(sites.get(orgId, call.parameters["id"]!!))
        }
        put("/sites/{id}") {
            val orgId = call.orgId()
            val req = call.receive<UpdateSiteRequest>()
            call.respond(sites.update(orgId, call.parameters["id"]!!, req))
        }
        patch("/sites/{id}") {
            val orgId = call.orgId()
            val req = call.receive<UpdateSiteRequest>()
            call.respond(sites.update(orgId, call.parameters["id"]!!, req))
        }
        delete("/sites/{id}") {
            val orgId = call.orgId()
            val id = call.parameters["id"]!!
            sites.get(orgId, id)
            if (assets.countOnSite(orgId, id) > 0) {
                throw ConflictException("Site has assets; move or delete them first")
            }
            sites.delete(orgId, id)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/assets") {
            val orgId = call.orgId()
            val req = call.receive<CreateAssetRequest>()
            if (!sites.exists(orgId, req.siteId)) {
                throw IllegalArgumentException("Unknown siteId")
            }
            call.respond(HttpStatusCode.Created, assets.create(orgId, req))
        }
        get("/assets") {
            val orgId = call.orgId()
            val siteId = call.request.queryParameters["siteId"]?.takeIf { it.isNotBlank() }
            val listed = assets.list(orgId, siteId)
            val items =
                if (call.scopeFilterEnabled()) {
                    scopes.filterAllowed(orgId, call.userId(), listed)
                } else {
                    listed
                }
            call.respond(AssetList(items = items))
        }
        get("/assets/{id}") {
            val orgId = call.orgId()
            call.respond(assets.get(orgId, call.parameters["id"]!!))
        }
        patch("/assets/{id}") {
            val orgId = call.orgId()
            val req = call.receive<UpdateAssetRequest>()
            call.respond(assets.update(orgId, call.parameters["id"]!!, req))
        }
        post("/assets/{id}/move") {
            val orgId = call.orgId()
            val req = call.receive<MoveAssetRequest>()
            if (!sites.exists(orgId, req.siteId)) {
                throw IllegalArgumentException("Unknown siteId")
            }
            call.respond(assets.move(orgId, call.parameters["id"]!!, req.siteId))
        }
        post("/assets/{id}/confirm") {
            val orgId = call.orgId()
            call.respond(assets.confirm(orgId, call.parameters["id"]!!))
        }
        post("/assets/{id}/reject") {
            val orgId = call.orgId()
            assets.reject(orgId, call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/assets/{id}") {
            val orgId = call.orgId()
            assets.delete(orgId, call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/assets/{id}/unlink-documents") {
            val orgId = call.orgId()
            val req = call.receive<UnlinkDocumentsRequest>()
            call.respond(assets.unlinkDocumentIds(orgId, call.parameters["id"]!!, req.documentIds))
        }

        get("/user-scopes/candidates/{assetId}") {
            val orgId = call.orgId()
            val assetId = call.parameters["assetId"]!!
            val asset = assets.get(orgId, assetId)
            call.respond(ScopeCandidatesResponse(userIds = scopes.candidates(orgId, asset)))
        }
        get("/user-scopes/{userId}") {
            val orgId = call.orgId()
            call.respond(scopes.get(orgId, call.parameters["userId"]!!))
        }
        put("/user-scopes/{userId}") {
            val orgId = call.orgId()
            val req = call.receive<PutUserScopeRequest>()
            call.respond(scopes.put(orgId, call.parameters["userId"]!!, req))
        }
        get("/user-scopes/{userId}/covers/{assetId}") {
            val orgId = call.orgId()
            val userId = call.parameters["userId"]!!
            val assetId = call.parameters["assetId"]!!
            val asset = assets.get(orgId, assetId)
            call.respond(CoversResponse(covers = scopes.covers(orgId, userId, asset)))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.orgId(): String =
    request.header("X-Org-Id")?.takeIf { it.isNotBlank() } ?: "default-org"

private fun io.ktor.server.application.ApplicationCall.userId(): String =
    request.header("X-User-Id")?.takeIf { it.isNotBlank() } ?: "unknown"

private fun io.ktor.server.application.ApplicationCall.scopeFilterEnabled(): Boolean {
    val value = request.header("X-Scope-Filter")?.trim()?.lowercase() ?: return false
    return value == "1" || value == "true"
}

class ConflictException(message: String) : RuntimeException(message)

@Serializable
enum class RecordStatus { draft, active }

@Serializable
enum class RecordSource { manual, ai_generated }

@Serializable
data class Site(
    val id: String,
    val orgId: String,
    val name: String,
    val address: String? = null,
)

@Serializable
data class CreateSiteRequest(
    val name: String,
    val address: String? = null,
    val id: String? = null,
)

@Serializable
data class UpdateSiteRequest(
    val name: String? = null,
    val address: String? = null,
)

@Serializable
data class SiteList(val items: List<Site>)

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
data class UpdateAssetRequest(
    val name: String? = null,
    val inventoryNo: String? = null,
    val category: String? = null,
    val description: String? = null,
    val documentIds: List<String>? = null,
)

@Serializable
data class MoveAssetRequest(val siteId: String)

@Serializable
data class UnlinkDocumentsRequest(val documentIds: List<String>)

@Serializable
data class AssetList(val items: List<Asset>)

@Serializable
data class UserScope(
    val userId: String,
    val orgId: String,
    val siteIds: List<String> = emptyList(),
    val assetIds: List<String> = emptyList(),
)

@Serializable
data class PutUserScopeRequest(
    val siteIds: List<String> = emptyList(),
    val assetIds: List<String> = emptyList(),
)

@Serializable
data class CoversResponse(val covers: Boolean)

@Serializable
data class ScopeCandidatesResponse(val userIds: List<String>)

class ScopeStore {
    private val byKey = ConcurrentHashMap<String, UserScope>()

    private fun key(orgId: String, userId: String) = "$orgId::$userId"

    fun get(orgId: String, userId: String): UserScope =
        byKey[key(orgId, userId)] ?: UserScope(userId = userId, orgId = orgId)

    fun put(orgId: String, userId: String, req: PutUserScopeRequest): UserScope {
        val scope =
            UserScope(
                userId = userId,
                orgId = orgId,
                siteIds = req.siteIds.distinct(),
                assetIds = req.assetIds.distinct(),
            )
        byKey[key(orgId, userId)] = scope
        return scope
    }

    fun covers(orgId: String, userId: String, asset: Asset): Boolean {
        val scope = get(orgId, userId)
        if (scope.siteIds.isEmpty() && scope.assetIds.isEmpty()) return false
        if (asset.id in scope.assetIds) return true
        if (asset.siteId in scope.siteIds) return true
        return false
    }

    fun candidates(orgId: String, asset: Asset): List<String> =
        byKey.values
            .filter { it.orgId == orgId && covers(orgId, it.userId, asset) }
            .map { it.userId }
            .sorted()

    fun filterAllowed(orgId: String, userId: String, assets: List<Asset>): List<Asset> =
        assets.filter { covers(orgId, userId, it) }
}

class SiteStore {
    private val byKey = ConcurrentHashMap<String, Site>()

    private fun key(orgId: String, id: String) = "$orgId::$id"

    fun create(orgId: String, req: CreateSiteRequest): Site {
        require(req.name.isNotBlank()) { "name required" }
        val id = req.id?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val k = key(orgId, id)
        require(byKey[k] == null) { "site id already exists" }
        val site =
            Site(
                id = id,
                orgId = orgId,
                name = req.name.trim(),
                address = req.address?.trim()?.takeIf { it.isNotEmpty() },
            )
        byKey[k] = site
        return site
    }

    fun list(orgId: String): List<Site> = byKey.values.filter { it.orgId == orgId }.sortedBy { it.name }

    fun get(orgId: String, id: String): Site =
        byKey[key(orgId, id)] ?: throw NoSuchElementException("Site not found")

    fun update(orgId: String, id: String, req: UpdateSiteRequest): Site {
        val current = get(orgId, id)
        val updated =
            current.copy(
                name = req.name?.trim()?.takeIf { it.isNotEmpty() } ?: current.name,
                address =
                    when {
                        req.address == null -> current.address
                        req.address.isBlank() -> null
                        else -> req.address.trim()
                    },
            )
        byKey[key(orgId, id)] = updated
        return updated
    }

    fun delete(orgId: String, id: String) {
        get(orgId, id)
        byKey.remove(key(orgId, id))
    }

    fun exists(orgId: String, id: String): Boolean = byKey.containsKey(key(orgId, id))
}

class AssetStore {
    private val byId = ConcurrentHashMap<String, Asset>()

    fun create(orgId: String, req: CreateAssetRequest): Asset {
        require(req.name.isNotBlank()) { "name required" }
        require(req.siteId.isNotBlank()) { "siteId required" }
        require(req.documentIds.size <= 1) { "at most one document" }
        requireDocumentsAvailable(orgId, req.documentIds, exceptAssetId = null)
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
                documentIds = req.documentIds.distinct().take(1),
            )
        byId[asset.id] = asset
        return asset
    }

    fun list(orgId: String, siteId: String? = null): List<Asset> =
        byId.values
            .filter { it.orgId == orgId && (siteId == null || it.siteId == siteId) }
            .sortedBy { it.name }

    fun get(orgId: String, id: String): Asset {
        val asset = byId[id] ?: throw NoSuchElementException("Asset not found")
        if (asset.orgId != orgId) throw NoSuchElementException("Asset not found")
        return asset
    }

    fun update(orgId: String, id: String, req: UpdateAssetRequest): Asset {
        val asset = get(orgId, id)
        req.documentIds?.let { require(it.size <= 1) { "at most one document" } }
        req.documentIds?.let { requireDocumentsAvailable(orgId, it, exceptAssetId = id) }
        val updated =
            asset.copy(
                name = req.name?.trim()?.takeIf { it.isNotEmpty() } ?: asset.name,
                inventoryNo = req.inventoryNo.patchNullable(asset.inventoryNo),
                category = req.category.patchNullable(asset.category),
                description = req.description.patchNullable(asset.description),
                documentIds =
                    req.documentIds?.let { documentIds ->
                        documentIds.distinct().take(1)
                    } ?: asset.documentIds,
            )
        byId[id] = updated
        return updated
    }

    fun move(orgId: String, id: String, siteId: String): Asset {
        require(siteId.isNotBlank()) { "siteId required" }
        val asset = get(orgId, id)
        val moved = asset.copy(siteId = siteId)
        byId[id] = moved
        return moved
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

    fun delete(orgId: String, id: String) {
        get(orgId, id)
        byId.remove(id)
    }

    fun findByDocumentId(orgId: String, documentId: String, exceptAssetId: String? = null): Asset? =
        byId.values.firstOrNull { asset ->
            asset.orgId == orgId &&
                asset.id != exceptAssetId &&
                documentId in asset.documentIds
        }

    private fun requireDocumentsAvailable(orgId: String, documentIds: List<String>, exceptAssetId: String?) {
        documentIds.forEach { docId ->
            val owner = findByDocumentId(orgId, docId, exceptAssetId)
            require(owner == null) { "document already bound to equipment" }
        }
    }

    fun countOnSite(orgId: String, siteId: String): Int =
        byId.values.count { it.orgId == orgId && it.siteId == siteId }

    fun unlinkDocumentIds(orgId: String, id: String, documentIds: List<String>): Asset {
        require(documentIds.isNotEmpty()) { "documentIds required" }
        val asset = get(orgId, id)
        val toRemove = documentIds.toSet()
        val updated = asset.copy(documentIds = asset.documentIds.filter { it !in toRemove })
        byId[id] = updated
        return updated
    }

    fun exists(orgId: String, id: String): Boolean = runCatching { get(orgId, id) }.isSuccess
}

private fun String?.patchNullable(current: String?): String? =
    when {
        this == null -> current
        isBlank() -> null
        else -> trim()
    }
