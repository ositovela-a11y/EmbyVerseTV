package com.embyverse.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

sealed interface ConfigParseResult {
    data class Valid(val config: RemoteConfig, val warnings: List<String>) : ConfigParseResult
    data class Invalid(val errors: List<String>) : ConfigParseResult
}

/**
 * Valida y convierte la configuración remota. Reglas (idénticas a backend/src/config/schema.ts):
 *  - Campos desconocidos: se ignoran (compatibilidad hacia delante).
 *  - Campos ausentes: se toman de la configuración base (valores predeterminados).
 *  - Valores presentes pero inválidos: la configuración COMPLETA se rechaza y la app conserva la última válida.
 *  - schemaVersion mayor que la soportada: se rechaza.
 *  - Tipos de sección desconocidos: se aceptan pero la app los omite (aviso), para que un panel más nuevo
 *    no rompa versiones antiguas de la app.
 */
object RemoteConfigParser {
    const val MAX_BYTES = 256 * 1024
    private val COLOR = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
    private val ID = Regex("^[a-z0-9_-]{1,32}$")
    private val TEXT_KEY = Regex("^[A-Za-z0-9_.]{1,64}$")
    private val TYPE_TOKEN = Regex("^[a-z_]{1,40}$")
    private val json = Json { isLenient = false }

    fun parse(text: String, base: RemoteConfig = RemoteConfig.DEFAULT): ConfigParseResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return ConfigParseResult.Invalid(listOf("La configuración supera ${MAX_BYTES / 1024} KB"))
        val root = try { json.parseToJsonElement(text) } catch (e: Exception) {
            return ConfigParseResult.Invalid(listOf("JSON no válido"))
        }
        if (root !is JsonObject) return ConfigParseResult.Invalid(listOf("La raíz debe ser un objeto"))
        val v = V()
        val cfg = v.root(root, base)
        return if (v.errors.isEmpty() && cfg != null) ConfigParseResult.Valid(cfg, v.warnings) else ConfigParseResult.Invalid(v.errors)
    }

    fun parseColor(s: String): Int? {
        if (!COLOR.matches(s)) return null
        val hex = s.substring(1)
        val v = hex.toLong(16)
        return if (hex.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    /** Fecha ISO-8601 (2026-10-29T10:00:00Z o con desfase) → epoch en segundos. */
    fun parseIso(s: String): Long? {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?)?\s*(Z|[+-]\d{2}:?\d{2})?$""").find(s.trim()) ?: return null
        val g = m.groupValues
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
        val h = g[4].ifEmpty { "0" }.toInt(); val mi = g[5].ifEmpty { "0" }.toInt(); val sec = g[6].ifEmpty { "0" }.toInt()
        if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || sec > 59) return null
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
        val days = era * 146097L + (yoe * 365 + yoe / 4 - yoe / 100 + doy) - 719468
        var epoch = days * 86400 + h * 3600 + mi * 60 + sec
        val tz = g[7]
        if (tz.isNotEmpty() && tz != "Z") {
            val digits = tz.substring(1).replace(":", "")
            val off = digits.substring(0, 2).toInt() * 3600 + digits.substring(2, 4).toInt() * 60
            epoch -= if (tz[0] == '+') off else -off
        }
        return epoch
    }

    private class V {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        fun err(path: String, msg: String) { if (errors.size < 50) errors += "$path: $msg" }

        fun obj(o: JsonObject, k: String, path: String): JsonObject? = when (val e = o[k]) {
            null, JsonNull -> null
            is JsonObject -> e
            else -> { err("$path.$k", "debe ser un objeto"); null }
        }
        fun arr(o: JsonObject, k: String, path: String): JsonArray? = when (val e = o[k]) {
            null, JsonNull -> null
            is JsonArray -> e
            else -> { err("$path.$k", "debe ser una lista"); null }
        }
        fun str(o: JsonObject, k: String, path: String, min: Int, max: Int, nullable: Boolean = false): String? {
            val e = o[k] ?: return null
            if (e is JsonNull) { if (!nullable) err("$path.$k", "no puede ser null"); return null }
            if (e !is JsonPrimitive || !e.isString) { err("$path.$k", "debe ser texto"); return null }
            val s = e.content
            if (s.length < min || s.length > max) { err("$path.$k", "longitud debe estar entre $min y $max"); return null }
            if (s.any { it.code < 32 && it != '\n' }) { err("$path.$k", "contiene caracteres de control"); return null }
            return s
        }
        fun int(o: JsonObject, k: String, path: String, range: IntRange): Int? {
            val e = o[k] ?: return null
            val p = e as? JsonPrimitive
            val n = p?.takeIf { !it.isString }?.intOrNull
            if (n == null || p.content.contains('.')) { err("$path.$k", "debe ser un entero"); return null }
            if (n !in range) { err("$path.$k", "debe estar entre ${range.first} y ${range.last}"); return null }
            return n
        }
        fun num(o: JsonObject, k: String, path: String, min: Double, max: Double): Double? {
            val e = o[k] ?: return null
            val n = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if (n == null) { err("$path.$k", "debe ser un número"); return null }
            if (n < min || n > max) { err("$path.$k", "debe estar entre $min y $max"); return null }
            return n
        }
        fun bool(o: JsonObject, k: String, path: String): Boolean? {
            val e = o[k] ?: return null
            val b = (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            if (b == null) err("$path.$k", "debe ser true o false")
            return b
        }
        fun color(o: JsonObject, k: String, path: String): Int? {
            val s = str(o, k, path, 4, 9) ?: return null
            return parseColor(s) ?: run { err("$path.$k", "color no válido (usa #RRGGBB o #AARRGGBB)"); null }
        }
        fun <T> enum(o: JsonObject, k: String, path: String, values: Map<String, T>): T? {
            val s = str(o, k, path, 1, 40) ?: return null
            return values[s] ?: run { err("$path.$k", "valor no permitido '$s' (permitidos: ${values.keys.joinToString()})"); null }
        }
        fun httpsUrl(o: JsonObject, k: String, path: String): String? {
            val s = str(o, k, path, 9, 500, nullable = true) ?: return null
            if (!s.startsWith("https://") || s.any { it.isWhitespace() } || runCatching { java.net.URI(s).host }.getOrNull().isNullOrBlank()) {
                err("$path.$k", "debe ser una URL https válida"); return null
            }
            return s
        }
        fun iso(o: JsonObject, k: String, path: String): Long? {
            val s = str(o, k, path, 10, 40, nullable = true) ?: return null
            return parseIso(s) ?: run { err("$path.$k", "fecha ISO-8601 no válida"); null }
        }
        fun palette(o: JsonObject?, path: String): Map<String, Int> {
            if (o == null) return emptyMap()
            val out = LinkedHashMap<String, Int>()
            for (k in Palette.KEYS) color(o, k, path)?.let { out[k] = it }
            for (k in o.keys) if (k !in Palette.KEYS) warnings += "$path.$k: clave de color desconocida, ignorada"
            return out
        }

        fun root(r: JsonObject, base: RemoteConfig): RemoteConfig? {
            val schema = int(r, "schemaVersion", "$", 1..1000)
            if (r["schemaVersion"] == null) err("$.schemaVersion", "es obligatorio")
            if (schema != null && schema > RemoteConfig.SUPPORTED_SCHEMA) {
                err("$.schemaVersion", "versión $schema no soportada por esta app (máx. ${RemoteConfig.SUPPORTED_SCHEMA}); actualiza la app")
                return null
            }
            val version = int(r, "version", "$", 0..Int.MAX_VALUE) ?: base.version
            val publishedAt = str(r, "publishedAt", "$", 10, 40, nullable = true)
            val minApp = int(r, "minAppVersionCode", "$", 1..Int.MAX_VALUE) ?: base.minAppVersionCode

            // --- brand ---
            var brand = base.brand
            obj(r, "brand", "$")?.let { b ->
                val p = "$.brand"
                val name = str(b, "appName", p, 1, 40)
                val tagline = if (b.containsKey("tagline")) str(b, "tagline", p, 0, 80, nullable = true) else brand.tagline
                var variant = brand.logoVariant; var logoUrl = brand.logoUrl
                obj(b, "logo", p)?.let { l ->
                    enum(l, "variant", "$p.logo", LogoVariant.entries.associateBy { it.wire })?.let { variant = it }
                    logoUrl = httpsUrl(l, "url", "$p.logo")
                    if (variant == LogoVariant.ASSET && logoUrl == null) err("$p.logo.url", "obligatorio cuando variant = asset")
                    if (variant != LogoVariant.ASSET) logoUrl = null
                }
                brand = Brand(name ?: brand.appName, variant, logoUrl, tagline)
            }

            // --- theme ---
            var theme = base.theme
            obj(r, "theme", "$")?.let { t ->
                val p = "$.theme"
                val pal = theme.palette.merge(palette(obj(t, "palette", p), "$p.palette"))
                theme = theme.copy(palette = pal)
                obj(t, "background", p)?.let { bg ->
                    enum(bg, "style", "$p.background", mapOf("solid" to BackgroundStyle.SOLID, "gradient" to BackgroundStyle.GRADIENT, "nebula" to BackgroundStyle.NEBULA))?.let { theme = theme.copy(backgroundStyle = it) }
                    color(bg, "gradientStart", "$p.background")?.let { theme = theme.copy(gradientStart = it) }
                    color(bg, "gradientEnd", "$p.background")?.let { theme = theme.copy(gradientEnd = it) }
                }
                obj(t, "typography", p)?.let { ty ->
                    enum(ty, "family", "$p.typography", FontChoice.entries.associateBy { it.wire })?.let { theme = theme.copy(font = it) }
                    num(ty, "scale", "$p.typography", 0.8, 1.4)?.let { theme = theme.copy(fontScale = it.toFloat()) }
                }
                obj(t, "shape", p)?.let { s ->
                    int(s, "cardRadius", "$p.shape", 0..32)?.let { theme = theme.copy(cardRadius = it) }
                    int(s, "buttonRadius", "$p.shape", 0..32)?.let { theme = theme.copy(buttonRadius = it) }
                    int(s, "bannerRadius", "$p.shape", 0..40)?.let { theme = theme.copy(bannerRadius = it) }
                }
                obj(t, "spacing", p)?.let { s ->
                    int(s, "screenPadding", "$p.spacing", 8..48)?.let { theme = theme.copy(screenPadding = it) }
                    int(s, "sectionGap", "$p.spacing", 8..64)?.let { theme = theme.copy(sectionGap = it) }
                    int(s, "cardGap", "$p.spacing", 4..32)?.let { theme = theme.copy(cardGap = it) }
                }
                obj(t, "glow", p)?.let { g ->
                    bool(g, "enabled", "$p.glow")?.let { theme = theme.copy(glowEnabled = it) }
                    num(g, "intensity", "$p.glow", 0.0, 1.0)?.let { theme = theme.copy(glowIntensity = it.toFloat()) }
                }
                enum(t, "buttonStyle", p, mapOf("filled" to ButtonStyle.FILLED, "tonal" to ButtonStyle.TONAL, "outlined" to ButtonStyle.OUTLINED))?.let { theme = theme.copy(buttonStyle = it) }
                obj(t, "navigation", p)?.let { n ->
                    enum(n, "style", "$p.navigation", NavStyle.entries.associateBy { it.wire })?.let { theme = theme.copy(navStyle = it) }
                    bool(n, "showLabels", "$p.navigation")?.let { theme = theme.copy(navShowLabels = it) }
                }
            }

            // --- themes ---
            val themes = arr(r, "themes", "$")?.let { a ->
                if (a.size > 12) err("$.themes", "máximo 12 temas")
                val ids = HashSet<String>()
                a.mapIndexedNotNull { i, el ->
                    val p = "$.themes[$i]"
                    val o = el as? JsonObject ?: run { err(p, "debe ser un objeto"); return@mapIndexedNotNull null }
                    val id = str(o, "id", p, 1, 32)
                    if (id == null) { if (o["id"] == null) err("$p.id", "es obligatorio"); return@mapIndexedNotNull null }
                    if (!ID.matches(id)) err("$p.id", "solo a-z, 0-9, _ y -")
                    if (!ids.add(id)) err("$p.id", "duplicado '$id'")
                    val name = str(o, "name", p, 1, 40) ?: run { if (o["name"] == null) err("$p.name", "es obligatorio"); id }
                    NamedTheme(id, name, palette(obj(o, "palette", p), "$p.palette"))
                }
            } ?: base.themes

            // --- home.sections ---
            val sections = obj(r, "home", "$")?.let { h -> arr(h, "sections", "$.home") }?.let { a ->
                if (a.isEmpty() || a.size > 30) err("$.home.sections", "debe tener entre 1 y 30 secciones")
                val ids = HashSet<String>()
                a.mapIndexedNotNull { i, el ->
                    val p = "$.home.sections[$i]"
                    val o = el as? JsonObject ?: run { err(p, "debe ser un objeto"); return@mapIndexedNotNull null }
                    val id = str(o, "id", p, 1, 32)
                    val rawType = str(o, "type", p, 1, 40)
                    if (id == null || rawType == null) { err(p, "id y type son obligatorios"); return@mapIndexedNotNull null }
                    if (!ID.matches(id)) err("$p.id", "solo a-z, 0-9, _ y -")
                    if (!ids.add(id)) err("$p.id", "duplicado '$id'")
                    if (!TYPE_TOKEN.matches(rawType)) err("$p.type", "formato no válido")
                    val type = SectionType.fromWire(rawType)
                    if (type == null) warnings += "$p.type: tipo '$rawType' desconocido para esta versión de la app; se omitirá"
                    val layout = enum(o, "layout", p, SectionLayout.entries.associateBy { it.wire }) ?: if (type == SectionType.HERO_BANNER) SectionLayout.HERO else SectionLayout.CAROUSEL
                    if (layout == SectionLayout.HERO && type != null && type != SectionType.HERO_BANNER) err("$p.layout", "'hero' solo se permite en secciones hero_banner")
                    var aspect = CardAspect.POSTER; var size = CardSize.M
                    obj(o, "card", p)?.let { c ->
                        enum(c, "aspect", "$p.card", CardAspect.entries.associateBy { it.wire })?.let { aspect = it }
                        enum(c, "size", "$p.card", CardSize.entries.associateBy { it.wire })?.let { size = it }
                    }
                    SectionSpec(
                        id = id, rawType = rawType, type = type,
                        enabled = bool(o, "enabled", p) ?: true,
                        title = str(o, "title", p, 0, 60) ?: "",
                        subtitle = str(o, "subtitle", p, 0, 100, nullable = true)?.ifBlank { null },
                        layout = layout, aspect = aspect, size = size,
                        maxItems = int(o, "maxItems", p, 1..100) ?: 20,
                    )
                }
            } ?: base.sections
            val sectionIds = sections.map { it.id }.toSet()

            // --- userCustomization ---
            var uc = base.userCustomization
            obj(r, "userCustomization", "$")?.let { u ->
                val p = "$.userCustomization"
                val accents = arr(u, "accentChoices", p)?.let { a ->
                    if (a.size > 12) err("$p.accentChoices", "máximo 12 colores")
                    a.mapIndexedNotNull { i, e ->
                        val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                        s?.let(::parseColor) ?: run { err("$p.accentChoices[$i]", "color no válido"); null }
                    }
                }
                val locked = arr(u, "lockedSections", p)?.let { a ->
                    a.mapIndexedNotNull { i, e ->
                        val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (s == null) { err("$p.lockedSections[$i]", "debe ser texto"); null }
                        else if (s !in sectionIds) { err("$p.lockedSections[$i]", "la sección '$s' no existe"); null } else s
                    }.toSet()
                }
                uc = UserCustomization(
                    allowThemeSelection = bool(u, "allowThemeSelection", p) ?: uc.allowThemeSelection,
                    allowAccentColor = bool(u, "allowAccentColor", p) ?: uc.allowAccentColor,
                    accentChoices = accents ?: uc.accentChoices,
                    allowCardSize = bool(u, "allowCardSize", p) ?: uc.allowCardSize,
                    allowHideSections = bool(u, "allowHideSections", p) ?: uc.allowHideSections,
                    allowReorderSections = bool(u, "allowReorderSections", p) ?: uc.allowReorderSections,
                    lockedSections = locked ?: uc.lockedSections.filter { it in sectionIds }.toSet(),
                )
            }

            // --- banners ---
            val banners = arr(r, "banners", "$")?.let { a ->
                if (a.size > 20) err("$.banners", "máximo 20 banners")
                val ids = HashSet<String>()
                a.mapIndexedNotNull { i, el ->
                    val p = "$.banners[$i]"
                    val o = el as? JsonObject ?: run { err(p, "debe ser un objeto"); return@mapIndexedNotNull null }
                    val id = str(o, "id", p, 1, 32) ?: run { err("$p.id", "es obligatorio"); return@mapIndexedNotNull null }
                    if (!ID.matches(id)) err("$p.id", "solo a-z, 0-9, _ y -")
                    if (!ids.add(id)) err("$p.id", "duplicado '$id'")
                    val placement = enum(o, "placement", p, BannerPlacement.entries.associateBy { it.wire }) ?: BannerPlacement.PROMO
                    val title = str(o, "title", p, 1, 80) ?: run { if (o["title"] == null) err("$p.title", "es obligatorio"); "" }
                    var route: AppRoute? = null
                    obj(o, "action", p)?.let { act ->
                        val rs = str(act, "route", "$p.action", 1, 32, nullable = true)
                        if (rs != null) route = AppRoute.fromWire(rs) ?: run { err("$p.action.route", "ruta interna no autorizada '$rs'"); null }
                    }
                    val start = iso(o, "startsAt", p); val end = iso(o, "endsAt", p)
                    if (start != null && end != null && end <= start) err("$p.endsAt", "debe ser posterior a startsAt")
                    Banner(id, placement, title, str(o, "subtitle", p, 0, 160, nullable = true)?.ifBlank { null },
                        httpsUrl(o, "imageUrl", p), route, start, end)
                }
            } ?: base.banners

            // --- texts ---
            val texts = obj(r, "texts", "$")?.let { t ->
                if (t.size > 100) err("$.texts", "máximo 100 textos")
                t.entries.mapNotNull { (k, e) ->
                    if (!TEXT_KEY.matches(k)) { err("$.texts.$k", "clave no válida"); return@mapNotNull null }
                    val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (s == null || s.length > 500) { err("$.texts.$k", "debe ser texto de hasta 500 caracteres"); null } else k to s
                }.toMap()
            } ?: base.texts

            if (errors.isNotEmpty()) return null
            return RemoteConfig(schema ?: 1, version, publishedAt, minApp, brand, theme, themes, uc, sections, banners, texts)
        }
    }
}

/** Utilidad para pruebas y depuración: conversión de color ARGB a "#AARRGGBB". */
fun Int.toHexColor(): String = "#%08X".format(this)

@Suppress("unused")
private fun JsonElement.debug() = toString()
