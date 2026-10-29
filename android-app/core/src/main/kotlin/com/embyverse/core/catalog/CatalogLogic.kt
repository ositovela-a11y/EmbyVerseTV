package com.embyverse.core.catalog

import com.embyverse.core.model.ContentKind

/** Cambios a aplicar en la base local tras sincronizar una fuente (sin duplicar contenido). */
data class SyncPlan<T>(val upsert: List<T>, val deleteIds: Set<String>, val unchanged: Int)

object CatalogDiff {
    /**
     * @param existing huella actual por id (p. ej. hashCode de los campos relevantes)
     * @param incoming elementos recién descargados (pueden traer duplicados del proveedor)
     */
    fun <T> plan(existing: Map<String, Int>, incoming: List<T>, id: (T) -> String, fingerprint: (T) -> Int): SyncPlan<T> {
        val unique = LinkedHashMap<String, T>()
        for (item in incoming) unique.putIfAbsent(id(item), item)
        var unchanged = 0
        val upsert = unique.values.filter { item ->
            val same = existing[id(item)] == fingerprint(item)
            if (same) unchanged++
            !same
        }
        return SyncPlan(upsert, existing.keys - unique.keys, unchanged)
    }
}

data class RecItem(
    val key: String,
    val kind: ContentKind,
    val title: String,
    val genres: List<String>,
    val category: String?,
    val rating: Double?,
    val addedAtEpochSeconds: Long?,
)

/**
 * Recomendaciones locales basadas SOLO en los metadatos disponibles (géneros, categoría,
 * valoración y fecha de alta) y en lo que el perfil ha visto o marcado como favorito.
 * No se envía nada a ningún servidor.
 */
object Recommender {
    fun recommend(history: List<RecItem>, favorites: List<RecItem>, candidates: List<RecItem>, nowEpoch: Long, limit: Int = 20): List<RecItem> {
        val seen = (history + favorites).map { it.key }.toSet()
        val genreWeight = HashMap<String, Double>()
        val categoryWeight = HashMap<String, Double>()
        fun learn(items: List<RecItem>, w: Double) = items.forEach { i ->
            i.genres.forEach { g -> genreWeight.merge(g.lowercase(), w, Double::plus) }
            i.category?.let { c -> categoryWeight.merge(c.lowercase(), w, Double::plus) }
        }
        learn(history, 1.0); learn(favorites, 2.0)
        val coldStart = genreWeight.isEmpty() && categoryWeight.isEmpty()

        return candidates.asSequence()
            .filter { it.key !in seen && it.kind != ContentKind.LIVE && it.kind != ContentKind.EPISODE }
            .map { c ->
                val g = c.genres.sumOf { genreWeight[it.lowercase()] ?: 0.0 }
                val cat = c.category?.let { categoryWeight[it.lowercase()] } ?: 0.0
                val rating = (c.rating ?: 5.0) / 10.0
                val ageDays = c.addedAtEpochSeconds?.let { ((nowEpoch - it) / 86400.0).coerceAtLeast(0.0) }
                val fresh = ageDays?.let { 1.0 / (1.0 + it / 30.0) } ?: 0.0
                val score = if (coldStart) rating * 2 + fresh else g * 1.5 + cat + rating + fresh * 0.5
                c to score
            }
            .filter { coldStart || it.second > 1.0 }
            .sortedWith(compareByDescending<Pair<RecItem, Double>> { it.second }.thenBy { it.first.title })
            .take(limit).map { it.first }.toList()
    }
}
