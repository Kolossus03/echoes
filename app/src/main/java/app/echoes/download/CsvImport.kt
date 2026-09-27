package app.echoes.download

import app.echoes.library.fold

/**
 * Reads song lists exported from Spotify by Exportify, Chosic and similar tools. Their column
 * names are translated to the browser's language, so columns are found by what they contain
 * (a `spotify:track:` URI, an i.scdn.co image) and by names in several languages.
 */
object CsvImport {
    private val spotifyUri = Regex("""^spotify:track:([A-Za-z0-9]{22})$""")
    private val spotifyId = Regex("""^[A-Za-z0-9]{22}$""")
    private val clock = Regex("""^(\d+):(\d{2})$""")

    fun looksLikeCsv(name: String) = name.endsWith(".csv", ignoreCase = true)

    fun parse(fileName: String, text: String): Resolved {
        val rows = rows(text.removePrefix("﻿")).filter { r -> r.any { it.isNotBlank() } }
        require(rows.size >= 2) { "El archivo no tiene canciones" }
        val header = rows.first().map { fold(it.trim()) }
        val body = rows.drop(1)
        fun column(values: (String) -> Boolean): Int? = header.indices.firstOrNull { c ->
            val cells = body.mapNotNull { it.getOrNull(c)?.trim()?.takeIf(String::isNotEmpty) }
            cells.isNotEmpty() && cells.count(values) >= cells.size * 0.8
        }
        fun named(vararg words: String, not: List<String> = emptyList()): Int? =
            header.indices.firstOrNull { c -> words.any { it in header[c] } && not.none { it in header[c] } }

        val uriCol = column { spotifyUri.matches(it) }
        val idCol = if (uriCol == null) named("id", not = listOf("artist", "artista", "album", "isrc")) ?.takeIf { c -> body.all { spotifyId.matches(it.getOrNull(c)?.trim().orEmpty()) } } else null
        val imageCol = column { it.startsWith("https://i.scdn.co/image") || it.startsWith("https://image-cdn") }
        val titleCol = named("track name", "nombre de la cancion", "song", "title", "titulo", "cancion", "nom du titre", "titel", not = listOf("uri", "url", "preview", "vista previa", "numero", "number"))
            ?: throw IllegalArgumentException("No encuentro la columna con los títulos")
        val artistCol = named("artist name", "nombre(s) del artista", "artist", "artista", "interprete", "kunstler", not = listOf("uri", "album", "genre", "genero", "id"))
        val durationCol = named("duration", "duracion", "time", "tiempo", "duree", "dauer", not = listOf("added", "anadido"))

        val items = body.mapNotNull { r ->
            val title = r.getOrNull(titleCol)?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            val id = uriCol?.let { r.getOrNull(it)?.trim()?.let(spotifyUri::find)?.groupValues?.get(1) }
                ?: idCol?.let { r.getOrNull(it)?.trim() }
            Wanted.SpotifyTrack(
                id = id,
                title = title,
                artist = artistCol?.let { r.getOrNull(it) }?.trim().orEmpty()
                    .replace(Regex("""(?<!\\),(?=\S)"""), ", ")
                    .replace("\\,", ","),
                durationSec = durationCol?.let { seconds(r.getOrNull(it).orEmpty()) } ?: 0,
                thumb = imageCol?.let { r.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) },
            )
        }
        require(items.isNotEmpty()) { "El archivo no tiene canciones" }
        val name = fileName.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Importada" }
        return Resolved(items, name, Origin.FILE, fileName)
    }

    /** Milliseconds (Exportify) or m:ss (other exporters). */
    private fun seconds(cell: String): Long {
        val s = cell.trim()
        clock.matchEntire(s)?.let { return it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong() }
        val n = s.toDoubleOrNull() ?: return 0
        return if (n > 10_000) (n / 1000).toLong() else n.toLong()
    }

    /** RFC 4180: quoted fields may hold commas, line breaks and doubled quotes. Also accepts `;`. */
    private fun rows(text: String): List<List<String>> {
        val firstLine = text.substringBefore('\n')
        val sep = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == sep -> { row.add(cell.toString()); cell.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    out.add(row); row = ArrayList()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); out.add(row) }
        return out
    }
}
