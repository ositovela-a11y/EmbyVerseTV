package com.embyverse.app.data.sync

import android.content.Context
import android.util.Log
import com.embyverse.app.data.db.FavoriteEntity
import com.embyverse.app.data.db.ProfileEntity
import com.embyverse.app.data.db.ProgressEntity
import com.embyverse.app.di.AppContainer
import com.embyverse.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/**
 * Perfiles, favoritos y «seguir viendo» de la cuenta, iguales en TODOS los equipos donde se entra con la
 * misma cuenta Xtream (celular, Android TV, Samsung, LG y PC). Se guardan en el servidor de estadísticas
 * bajo una llave calculada aquí con servidor + usuario + contraseña (la contraseña nunca sale del equipo).
 * Combina en 3 vías (lo último sincronizado, lo de este equipo y lo del servidor), igual que la app de TV
 * (tv-app/src/account-sync.ts): lo agregado o borrado en cualquier equipo llega a los demás.
 */
class AccountSync(private val c: AppContainer, private val serviceUrl: () -> String? = { c.stats.serviceUrl() }) {
    private val sp = c.app.getSharedPreferences("account_sync", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    var lastResult: String = ""; private set

    fun enabled() = sp.getBoolean("enabled", true)
    fun setEnabled(v: Boolean) { sp.edit().putBoolean("enabled", v).apply() }

    suspend fun sync(): String = lock.withLock {
        withContext(Dispatchers.IO) {
            lastResult = runCatching { run() }.getOrElse { Log.d(TAG, "sin sincronizar: ${it.message}"); "sin conexión" }
            lastResult
        }
    }

    private suspend fun run(): String {
        if (!enabled()) return "apagado"
        val src = c.database.sources().all().firstOrNull { it.type == SourceType.XTREAM.name && !it.username.isNullOrBlank() } ?: return "sin cuenta Xtream"
        val pass = c.credentials.get(src.id) ?: return "sin contraseña"
        val host = runCatching { URI(if (src.location.contains("://")) src.location else "http://${src.location}").host?.lowercase() }.getOrNull() ?: return "servidor inválido"
        val key = sha256("ev-sync-v1|$host|${src.username}|$pass")
        val base = serviceUrl() ?: return "sin servidor"
        val url = "$base/v1/sync/$key"
        val baseDoc = sp.getString("base", null)?.takeIf { sp.getString("baseKey", null) == key.take(16) }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        var r = get(url)
        repeat(3) {
            val remote = (r["data"] as? JsonObject)?.takeIf { it["profiles"] is JsonArray } ?: EMPTY
            val uids = adoptUids(remote)
            val local = localDoc(src.id, uids, baseDoc)
            val merged = SyncMerge.mergeDoc(baseDoc, local, remote)
            apply(merged, src.id, uids)
            sp.edit().putString("base", merged.toString()).putString("baseKey", key.take(16)).apply()
            if (merged == r["data"]) return "ok"
            val body = JsonObject(mapOf("rev" to (r["rev"] ?: JsonPrimitive(0)), "data" to merged)).toString()
            c.okHttp.newCall(Request.Builder().url(url).put(body.toRequestBody("application/json".toMediaType())).build()).execute().use { resp ->
                if (resp.isSuccessful) return "ok"
                if (resp.code != 409) return "error ${resp.code}"
                r = json.parseToJsonElement(resp.body.string()).jsonObject // otro equipo guardó antes: se combina otra vez
            }
        }
        return "conflicto"
    }

    private fun get(url: String): JsonObject =
        c.okHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            json.parseToJsonElement(r.body.string()).jsonObject
        }

    /** Código de cada perfil en la cuenta. La primera vez, el perfil con el mismo nombre en la cuenta es el mismo (no se duplica). */
    private suspend fun adoptUids(remote: JsonObject): Map<Long, String> {
        val profiles = c.database.profiles().all()
        val used = profiles.mapNotNull { sp.getString("uid.${it.id}", null) }.toMutableSet()
        val remoteProfiles = remote["profiles"]!!.jsonArray.map { it.jsonObject }
        val out = HashMap<Long, String>()
        for (p in profiles) {
            val uid = sp.getString("uid.${p.id}", null) ?: (remoteProfiles.firstOrNull { r ->
                val u = r.str("uid"); u != null && u !in used && r.str("name")?.trim().equals(p.name.trim(), ignoreCase = true)
            }?.str("uid") ?: ("p" + UUID.randomUUID().toString().replace("-", "").take(18))).also { used += it; sp.edit().putString("uid.${p.id}", it).apply() }
            out[p.id] = uid
        }
        return out
    }

    private suspend fun localDoc(sourceId: String, uids: Map<Long, String>, base: JsonObject?): JsonObject {
        val baseProfiles = base?.get("profiles")?.jsonArray?.map { it.jsonObject }.orEmpty()
        val profiles = ArrayList<JsonElement>(); val favs = LinkedHashMap<String, JsonElement>(); val prog = LinkedHashMap<String, JsonElement>()
        for (p in c.database.profiles().all()) {
            val uid = uids[p.id] ?: continue
            // Se parte de lo ya sincronizado: los datos que este equipo no maneja (avatar de la TV, perfil infantil…) se conservan.
            val b = baseProfiles.firstOrNull { it.str("uid") == uid }
            profiles += JsonObject((b ?: JsonObject(emptyMap())) + mapOf("uid" to JsonPrimitive(uid), "name" to JsonPrimitive(p.name), "color" to JsonPrimitive(hex(p.colorArgb))))
            favs[uid] = JsonArray(c.database.library().favorites(p.id).filter { it.sourceId == sourceId && it.kind in FAV_KINDS }.map { f ->
                obj("kind" to f.kind.lowercase(), "id" to f.itemId, "title" to f.title, "image" to f.imageUrl, "at" to f.addedAt)
            })
            prog[uid] = JsonArray(c.database.library().history(p.id, 300).filter { it.sourceId == sourceId && it.kind in PROG_KINDS }.map { g ->
                obj("kind" to g.kind.lowercase(), "id" to g.itemId, "seriesId" to g.seriesId, "title" to g.title, "image" to g.imageUrl,
                    "pos" to g.positionMs, "dur" to g.durationMs, "at" to g.updatedAt, "season" to g.season, "episode" to g.episode, "done" to g.completed.takeIf { it })
            })
        }
        return JsonObject(mapOf("v" to JsonPrimitive(1), "profiles" to JsonArray(profiles), "favs" to JsonObject(favs), "prog" to JsonObject(prog)))
    }

    private suspend fun apply(doc: JsonObject, sourceId: String, uids: Map<Long, String>) {
        val db = c.database
        val list = doc["profiles"]!!.jsonArray.map { it.jsonObject }
        if (list.isEmpty()) return // nunca dejar el equipo sin perfiles
        val byUid = uids.entries.associate { (id, uid) -> uid to id }
        val existing = db.profiles().all().associateBy { it.id }
        val keep = HashSet<Long>()
        for (sp0 in list) {
            val uid = sp0.str("uid") ?: continue
            val name = sp0.str("name")?.take(30)?.ifBlank { null } ?: "Perfil"
            val color = sp0.str("color")?.let { parseHex(it) }
            val id = byUid[uid]?.takeIf { existing.containsKey(it) }
            val pid = if (id != null) {
                val cur = existing.getValue(id)
                if (cur.name != name || (color != null && cur.colorArgb != color)) db.profiles().upsert(cur.copy(name = name, colorArgb = color ?: cur.colorArgb))
                id
            } else db.profiles().upsert(ProfileEntity(name = name, colorArgb = color ?: COLORS[(uid.hashCode() and 0x7fffffff) % COLORS.size], createdAt = System.currentTimeMillis()))
                .also { sp.edit().putString("uid.$it", uid).apply() }
            keep += pid
            // Favoritos de la cuenta (solo los de esta cuenta; los de otras listas se quedan igual).
            val wantF = doc["favs"]?.jsonObject?.get(uid)?.jsonArray?.map { it.jsonObject }.orEmpty()
            val haveF = db.library().favorites(pid).filter { it.sourceId == sourceId && it.kind in FAV_KINDS }
            val wantKeys = wantF.mapNotNull { f -> f.str("kind")?.uppercase()?.let { "$it|${f.str("id")}" } }.toSet()
            haveF.filter { "${it.kind}|${it.itemId}" !in wantKeys }.forEach { db.library().removeFavorite(pid, it.kind, sourceId, it.itemId) }
            val haveKeys = haveF.map { "${it.kind}|${it.itemId}" }.toSet()
            for (f in wantF) {
                val kind = f.str("kind")?.uppercase()?.takeIf { it in FAV_KINDS } ?: continue
                val itemId = f.str("id") ?: continue
                if ("$kind|$itemId" in haveKeys) continue
                db.library().addFavorite(FavoriteEntity(pid, kind, sourceId, itemId, f.str("title").orEmpty(), f.str("image"), f.long("at") ?: System.currentTimeMillis()))
            }
            // «Seguir viendo»: se guarda lo más reciente.
            val wantP = doc["prog"]?.jsonObject?.get(uid)?.jsonArray?.map { it.jsonObject }.orEmpty()
            for (g in wantP) {
                val kind = g.str("kind")?.uppercase()?.takeIf { it in PROG_KINDS } ?: continue
                val itemId = g.str("id") ?: continue
                val at = g.long("at") ?: continue
                val cur = db.library().progress(pid, kind, sourceId, itemId)
                if (cur != null && cur.updatedAt >= at) continue
                db.library().saveProgress(ProgressEntity(pid, kind, sourceId, itemId, g.str("title").orEmpty(), g.str("image"), g.long("pos") ?: 0, g.long("dur") ?: 0,
                    g["done"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false, at, g.str("seriesId"), g.int("season"), g.int("episode")))
            }
        }
        // Perfiles borrados en otro equipo.
        for ((id, _) in uids) if (id !in keep && existing.containsKey(id)) {
            db.library().clearHistory(id); db.library().clearFavorites(id); db.profiles().delete(id)
            c.prefs.deleteProfile(id); sp.edit().remove("uid.$id").apply()
        }
        if (db.profiles().all().none { it.id == c.prefs.activeProfile() }) db.profiles().all().firstOrNull()?.let { c.prefs.setActiveProfile(it.id) }
    }

    companion object {
        private const val TAG = "EmbyVerseSync"
        val FAV_KINDS = setOf("LIVE", "MOVIE", "SERIES")
        val PROG_KINDS = setOf("MOVIE", "EPISODE")
        val EMPTY = JsonObject(mapOf("v" to JsonPrimitive(1), "profiles" to JsonArray(emptyList()), "favs" to JsonObject(emptyMap()), "prog" to JsonObject(emptyMap())))
        private val COLORS = intArrayOf(0xFF7BE52F.toInt(), 0xFF22D3EE.toInt(), 0xFFF472B6.toInt(), 0xFFFFB020.toInt(), 0xFFA78BFA.toInt(), 0xFFFF6B6B.toInt())

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
        fun parseHex(s: String): Int? = s.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        private fun obj(vararg kv: Pair<String, Any?>) = JsonObject(kv.filter { it.second != null }.associate { (k, v) ->
            k to when (v) { is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
        })
    }
}

/** La misma combinación en 3 vías que la app de TV (ver tv-app/src/account-sync.ts). */
object SyncMerge {
    private const val MAX_FAVS = 300
    private const val MAX_PROG = 60

    fun <T> merge3(base: List<T>?, local: List<T>, remote: List<T>, key: (T) -> String, pick: (T, T, T?) -> T): List<T> {
        val b = base?.associateBy(key); val l = local.associateBy(key); val r = remote.associateBy(key)
        val out = ArrayList<T>()
        for (k in r.keys + l.keys.filter { it !in r }) {
            val lv = l[k]; val rv = r[k]; val bv = b?.get(k)
            when {
                lv != null && rv != null -> out += pick(lv, rv, bv)
                lv != null -> if (base == null || bv == null) out += lv   // nuevo aquí (si estaba antes, lo borraron en otro equipo)
                rv != null -> if (base == null || bv == null) out += rv   // nuevo en otro equipo (si estaba antes, lo borraron aquí)
            }
        }
        return out
    }

    private fun at(o: JsonObject) = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    /** Gana el más reciente; los datos que solo trae el otro (p. ej. «ext» de la TV) se conservan. */
    private fun newer(l: JsonObject, r: JsonObject, @Suppress("UNUSED_PARAMETER") b: JsonObject?) = if (at(l) >= at(r)) JsonObject(r + l) else JsonObject(l + r)
    private fun pickProfile(l: JsonObject, r: JsonObject, b: JsonObject?): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (f in r.keys + l.keys) {
            val v = if (b != null && l[f] != b[f]) l[f] else r[f] // cambiado aquí → gana lo de aquí; si no, lo del servidor
            if (v != null) out[f] = v
        }
        return JsonObject(out)
    }
    private fun list(o: JsonObject?, k: String) = (o?.get(k) as? JsonArray)?.mapNotNull { it as? JsonObject }
    private fun sub(o: JsonObject?, k: String, uid: String) = ((o?.get(k) as? JsonObject)?.get(uid) as? JsonArray)?.mapNotNull { it as? JsonObject }

    fun mergeDoc(base: JsonObject?, local: JsonObject, remote: JsonObject): JsonObject {
        val profiles = merge3(list(base, "profiles"), list(local, "profiles").orEmpty(), list(remote, "profiles").orEmpty(), { s(it, "uid").orEmpty() }, ::pickProfile)
        val favs = LinkedHashMap<String, JsonElement>(); val prog = LinkedHashMap<String, JsonElement>()
        for (p in profiles) {
            val uid = s(p, "uid") ?: continue
            favs[uid] = JsonArray(merge3(sub(base, "favs", uid), sub(local, "favs", uid).orEmpty(), sub(remote, "favs", uid).orEmpty(), { "${s(it, "kind")}:${s(it, "id")}" }, ::newer)
                .sortedByDescending(::at).take(MAX_FAVS))
            val seen = HashSet<String>()
            prog[uid] = JsonArray(merge3(sub(base, "prog", uid), sub(local, "prog", uid).orEmpty(), sub(remote, "prog", uid).orEmpty(), { "${s(it, "kind")}:${s(it, "id")}" }, ::newer)
                .sortedByDescending(::at).filter { seen.add(s(it, "seriesId")?.let { sid -> "s:$sid" } ?: "${s(it, "kind")}:${s(it, "id")}") }.take(MAX_PROG))
        }
        return JsonObject(mapOf("v" to JsonPrimitive(1), "profiles" to JsonArray(profiles), "favs" to JsonObject(favs), "prog" to JsonObject(prog)))
    }
}
