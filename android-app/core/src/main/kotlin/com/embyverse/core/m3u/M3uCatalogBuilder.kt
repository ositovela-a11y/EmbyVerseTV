package com.embyverse.core.m3u

import com.embyverse.core.model.Catalog
import com.embyverse.core.model.Category
import com.embyverse.core.model.ContentKind
import com.embyverse.core.model.Episode
import com.embyverse.core.model.LiveChannel
import com.embyverse.core.model.Movie
import com.embyverse.core.model.Series
import com.embyverse.core.util.StableId
import java.text.Normalizer

/**
 * Clasifica las entradas de una lista M3U en TV en vivo, películas y series.
 *
 * Las listas M3U no tienen un campo estándar de tipo, así que se usan señales en este orden
 * (de más a menos fiable). Todas están cubiertas por pruebas en M3uParserTest:
 *  1. Ruta de la URL estilo Xtream: /movie/ → película, /series/ → episodio, /live/ → vivo.
 *  2. Atributo explícito tvg-type / type ("movie", "series", "live").
 *  3. Extensión de archivo de VOD (.mp4, .mkv, ...) → película, o episodio si el título tiene SxxEyy.
 *  4. Título con patrón de episodio (S01E02, S01 E02, 1x02) en grupo no "en vivo" → episodio.
 *  5. Grupo con palabras clave de VOD/series.
 *  6. Por defecto → canal en vivo.
 */
class M3uCatalogBuilder {

    fun build(sourceId: String, playlist: M3uPlaylist): Catalog {
        val live = ArrayList<LiveChannel>()
        val movies = ArrayList<Movie>()
        val seriesMap = LinkedHashMap<String, MutableSeries>()
        val categories = LinkedHashSet<Pair<ContentKind, String>>()

        for (e in playlist.entries) {
            val kind = classify(e)
            val id = StableId.of(e.url)
            val group = e.group
            when (kind) {
                ContentKind.LIVE -> {
                    live += LiveChannel(
                        sourceId = sourceId, providerId = id, name = e.title, streamUrl = e.url,
                        logoUrl = e.attr("tvg-logo", "logo")?.takeIf { it.startsWith("http", true) },
                        categoryName = group, epgChannelId = e.attr("tvg-id"),
                        number = e.attr("tvg-chno", "channel-number")?.toIntOrNull(),
                        headers = e.headers,
                        catchupDays = e.attr("catchup-days", "timeshift")?.toIntOrNull() ?: 0,
                    )
                    group?.let { categories += ContentKind.LIVE to it }
                }
                ContentKind.MOVIE -> {
                    val (cleanTitle, year) = extractYear(e.title)
                    movies += Movie(
                        sourceId = sourceId, providerId = id, title = cleanTitle, streamUrl = e.url,
                        posterUrl = e.attr("tvg-logo", "logo")?.takeIf { it.startsWith("http", true) },
                        categoryName = group, year = year,
                        durationSeconds = e.durationSeconds.takeIf { it > 0 }?.toLong(),
                        containerExtension = extension(e.url), headers = e.headers,
                    )
                    group?.let { categories += ContentKind.MOVIE to it }
                }
                ContentKind.EPISODE, ContentKind.SERIES -> {
                    val parsed = parseEpisodeTitle(e.title)
                    val seriesTitle = parsed?.seriesTitle?.ifBlank { null } ?: group ?: e.title
                    val key = normalize(seriesTitle)
                    val s = seriesMap.getOrPut(key) {
                        MutableSeries(StableId.of("series:$key"), seriesTitle, group, e.attr("tvg-logo", "logo"))
                    }
                    s.episodes += Episode(
                        sourceId = sourceId, providerId = id, seriesProviderId = s.id,
                        season = parsed?.season ?: 1,
                        episode = parsed?.episode ?: (s.episodes.count { it.season == (parsed?.season ?: 1) } + 1),
                        title = parsed?.episodeTitle?.ifBlank { null } ?: e.title,
                        streamUrl = e.url,
                        thumbnailUrl = e.attr("tvg-logo", "logo")?.takeIf { it.startsWith("http", true) },
                        durationSeconds = e.durationSeconds.takeIf { it > 0 }?.toLong(),
                        headers = e.headers,
                    )
                    group?.let { categories += ContentKind.SERIES to it }
                }
            }
        }

        val series = seriesMap.values.map { m ->
            // Eliminar episodios duplicados (mismo número de temporada y episodio): se queda el primero.
            val unique = m.episodes.distinctBy { it.season to it.episode }
            Series(sourceId, m.id, m.title, posterUrl = m.poster?.takeIf { it.startsWith("http", true) },
                categoryName = m.group, episodes = unique)
        }
        return Catalog(
            sourceId = sourceId, live = live, movies = movies, series = series,
            categories = categories.map { (k, n) -> Category(sourceId, k, StableId.of("$k:$n"), n) },
            epgUrl = playlist.epgUrl,
            warnings = buildList { if (playlist.skippedLines > 0) add("${playlist.skippedLines} líneas no válidas se omitieron") },
        )
    }

    private class MutableSeries(val id: String, val title: String, val group: String?, val poster: String?) {
        val episodes = ArrayList<Episode>()
    }

    data class EpisodeTitle(val seriesTitle: String, val season: Int, val episode: Int, val episodeTitle: String?)

    companion object {
        private val VOD_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "webm", "mpg", "mpeg", "flv")
        private val LIVE_EXT = setOf("ts", "m3u8", "m3u")
        private val EPISODE_PATTERNS = listOf(
            Regex("""^(.*?)[\s._\-:|]*[Ss](\d{1,2})[\s._\-]*[EeXx](\d{1,4})\b[\s._\-:|]*(.*)$"""),
            Regex("""^(.*?)[\s._\-:|]+(\d{1,2})[xX](\d{1,3})\b[\s._\-:|]*(.*)$"""),
            Regex("""^(.*?)[\s._\-:|]*(?:Temporada|Season|Temp\.?)\s*(\d{1,2})[\s,._\-:|]*(?:Episodio|Episode|Cap(?:í|i)tulo|Cap\.?|Ep\.?)\s*(\d{1,4})\b[\s._\-:|]*(.*)$""", RegexOption.IGNORE_CASE),
        )
        private val MOVIE_GROUP = Regex("""\b(vod|movies?|pel[ií]culas?|films?|cine|cinema|estrenos)\b""", RegexOption.IGNORE_CASE)
        private val SERIES_GROUP = Regex("""\b(series?|tv\s*shows?|shows|novelas?|anime)\b""", RegexOption.IGNORE_CASE)
        private val LIVE_GROUP = Regex("""\b(live|en\s*vivo|directo|24/7|canales|channels?|sports?|deportes|news|noticias)\b""", RegexOption.IGNORE_CASE)

        fun classify(e: M3uEntry): ContentKind {
            val path = runCatching { java.net.URI(e.url).path.orEmpty() }.getOrDefault("").lowercase()
            when {
                path.contains("/movie/") || path.contains("/movies/") -> return ContentKind.MOVIE
                path.contains("/series/") -> return ContentKind.EPISODE
                path.contains("/live/") -> return ContentKind.LIVE
            }
            when (e.attr("tvg-type", "type")?.lowercase()) {
                "movie", "movies", "vod" -> return ContentKind.MOVIE
                "series", "serie", "episode" -> return ContentKind.EPISODE
                "live", "channel", "tv" -> return ContentKind.LIVE
            }
            val ext = extension(e.url)
            val looksEpisode = parseEpisodeTitle(e.title) != null
            val group = e.group.orEmpty()
            if (ext != null && ext in VOD_EXT) return if (looksEpisode || SERIES_GROUP.containsMatchIn(group)) ContentKind.EPISODE else ContentKind.MOVIE
            if (looksEpisode && !LIVE_GROUP.containsMatchIn(group) && ext !in LIVE_EXT) return ContentKind.EPISODE
            if (ext !in LIVE_EXT) {
                if (SERIES_GROUP.containsMatchIn(group) && looksEpisode) return ContentKind.EPISODE
                if (MOVIE_GROUP.containsMatchIn(group) && !LIVE_GROUP.containsMatchIn(group)) return ContentKind.MOVIE
            }
            return ContentKind.LIVE
        }

        fun parseEpisodeTitle(title: String): EpisodeTitle? {
            for (p in EPISODE_PATTERNS) {
                val m = p.find(title.trim()) ?: continue
                val s = m.groupValues[2].toIntOrNull() ?: continue
                val ep = m.groupValues[3].toIntOrNull() ?: continue
                if (s > 99 || ep > 9999) continue
                return EpisodeTitle(m.groupValues[1].trim(' ', '-', '|', ':', '.', '_'), s, ep, m.groupValues[4].trim().ifBlank { null })
            }
            return null
        }

        fun extension(url: String): String? {
            val path = runCatching { java.net.URI(url).path }.getOrNull() ?: url.substringBefore('?')
            val last = path.substringAfterLast('/')
            val dot = last.lastIndexOf('.')
            return if (dot in 1 until last.length - 1) last.substring(dot + 1).lowercase().takeIf { it.length <= 5 } else null
        }

        private val YEAR = Regex("""^(.*?)[\s._\-]*[(\[]?((?:19|20)\d{2})[)\]]?\s*$""")
        fun extractYear(title: String): Pair<String, Int?> {
            val m = YEAR.find(title.trim()) ?: return title.trim() to null
            val t = m.groupValues[1].trim(' ', '-', '.', '_')
            return if (t.isEmpty()) title.trim() to null else t to m.groupValues[2].toInt()
        }

        fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
