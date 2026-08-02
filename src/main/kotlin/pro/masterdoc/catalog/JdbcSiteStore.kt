package pro.masterdoc.catalog

import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

class JdbcSiteStore(private val dataSource: DataSource) {
    fun create(orgId: String, req: CreateSiteRequest): Site {
        require(req.name.isNotBlank()) { "name required" }
        validateGeofence(req.lat, req.lon, req.geofenceRadiusM)
        val site = Site(
            id = req.id?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(),
            orgId = orgId,
            name = req.name.trim(),
            address = req.address?.trim()?.takeIf { it.isNotEmpty() },
            lat = req.lat,
            lon = req.lon,
            geofenceRadiusM = req.geofenceRadiusM,
        )
        try {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO sites (id, org_id, name, address, lat, lon, geofence_radius_m) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { s ->
                    s.setString(1, site.id)
                    s.setString(2, orgId)
                    s.setString(3, site.name)
                    s.setString(4, site.address)
                    if (site.lat == null) s.setNull(5, Types.DOUBLE) else s.setDouble(5, site.lat)
                    if (site.lon == null) s.setNull(6, Types.DOUBLE) else s.setDouble(6, site.lon)
                    if (site.geofenceRadiusM == null) s.setNull(7, Types.INTEGER) else s.setInt(7, site.geofenceRadiusM)
                    s.executeUpdate()
                }
            }
        } catch (_: SQLException) {
            throw IllegalArgumentException("site id already exists")
        }
        return site
    }

    fun list(orgId: String): List<Site> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT id, org_id, name, address, lat, lon, geofence_radius_m FROM sites WHERE org_id = ? ORDER BY name",
            ).use { s ->
                s.setString(1, orgId)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.toSite())
                    }
                }
            }
        }

    fun ensureDefaultIfEmpty(orgId: String): List<Site> {
        val current = list(orgId)
        if (current.isNotEmpty()) return current
        try {
            create(orgId, CreateSiteRequest(id = "ceh-1", name = "Цех 1"))
        } catch (_: IllegalArgumentException) {
            // Another request seeded the default concurrently.
        }
        return list(orgId)
    }

    fun get(orgId: String, id: String): Site =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT id, org_id, name, address, lat, lon, geofence_radius_m FROM sites WHERE org_id = ? AND id = ?",
            ).use { s ->
                s.setString(1, orgId)
                s.setString(2, id)
                s.executeQuery().use { rs ->
                    if (!rs.next()) throw NoSuchElementException("Site not found")
                    rs.toSite()
                }
            }
        }

    fun update(orgId: String, id: String, req: UpdateSiteRequest): Site {
        val current = get(orgId, id)
        validateGeofence(req.lat, req.lon, req.geofenceRadiusM)
        val updated = current.copy(
            name = req.name?.trim()?.takeIf { it.isNotEmpty() } ?: current.name,
            address = when {
                req.address == null -> current.address
                req.address.isBlank() -> null
                else -> req.address.trim()
            },
            lat = req.lat ?: current.lat,
            lon = req.lon ?: current.lon,
            geofenceRadiusM = req.geofenceRadiusM ?: current.geofenceRadiusM,
        )
        dataSource.connection.use { c ->
            c.prepareStatement(
                "UPDATE sites SET name = ?, address = ?, lat = ?, lon = ?, geofence_radius_m = ? WHERE org_id = ? AND id = ?",
            ).use { s ->
                s.setString(1, updated.name)
                s.setString(2, updated.address)
                if (updated.lat == null) s.setNull(3, Types.DOUBLE) else s.setDouble(3, updated.lat)
                if (updated.lon == null) s.setNull(4, Types.DOUBLE) else s.setDouble(4, updated.lon)
                if (updated.geofenceRadiusM == null) s.setNull(5, Types.INTEGER) else s.setInt(5, updated.geofenceRadiusM)
                s.setString(6, orgId)
                s.setString(7, id)
                s.executeUpdate()
            }
        }
        return updated
    }

    fun delete(orgId: String, id: String) {
        get(orgId, id)
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM sites WHERE org_id = ? AND id = ?").use { s ->
                s.setString(1, orgId)
                s.setString(2, id)
                s.executeUpdate()
            }
        }
    }

    fun exists(orgId: String, id: String): Boolean =
        runCatching { get(orgId, id) }.isSuccess

    private fun validateGeofence(lat: Double?, lon: Double?, geofenceRadiusM: Int?) {
        lat?.let { require(it in -90.0..90.0) { "lat must be between -90 and 90" } }
        lon?.let { require(it in -180.0..180.0) { "lon must be between -180 and 180" } }
        geofenceRadiusM?.let { require(it > 0) { "geofenceRadiusM must be greater than 0" } }
    }

    private fun java.sql.ResultSet.toSite() = Site(
        id = getString("id"),
        orgId = getString("org_id"),
        name = getString("name"),
        address = getString("address"),
        lat = getObject("lat")?.let { (it as Number).toDouble() },
        lon = getObject("lon")?.let { (it as Number).toDouble() },
        geofenceRadiusM = getObject("geofence_radius_m")?.let { (it as Number).toInt() },
    )
}
