package com.example.dink_smb_player.data.index

/**
 * Genre tag helpers. [resolveDisplay] turns the numeric ID3 forms ("(17)", "17", ID3v1's
 * genre byte) into names; [keysOf] reduces a raw tag to normalized match keys for radio
 * similarity — never shown to the user. A multi-genre tag yields several keys and two
 * tracks match on any overlap.
 */
object GenreKeys {

    /** ID3v1 genre table, 0–79 standard plus the Winamp extensions to 191. 255 = none. */
    val ID3V1: List<String> = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
        "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap",
        "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
        "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
        "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock",
        "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
        "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi",
        "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebob", "Latin", "Revival",
        "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock",
        "Big Band", "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera",
        "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire", "Slow Jam",
        "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A capella", "Euro-House", "Dance Hall", "Goa", "Drum & Bass",
        "Club-House", "Hardcore", "Terror", "Indie", "BritPop", "Afro-Punk", "Polsk Punk", "Beat",
        "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover", "Contemporary Christian", "Christian Rock", "Merengue", "Salsa",
        "Thrash Metal", "Anime", "JPop", "Synthpop", "Abstract", "Art Rock", "Baroque", "Bhangra",
        "Big Beat", "Breakbeat", "Chillout", "Downtempo", "Dub", "EBM", "Eclectic", "Electro",
        "Electroclash", "Emo", "Experimental", "Garage", "Global", "IDM", "Illbient", "Industro-Goth",
        "Jam Band", "Krautrock", "Leftfield", "Lounge", "Math Rock", "New Romantic", "Nu-Breakz", "Post-Punk",
        "Post-Rock", "Psytrance", "Shoegaze", "Space Rock", "Trop Rock", "World Music", "Neoclassical", "Audiobook",
        "Audio Theatre", "Neue Deutsche Welle", "Podcast", "Indie Rock", "G-Funk", "Dubstep", "Garage Rock", "Psybient",
    )

    fun id3v1Name(index: Int): String? = ID3V1.getOrNull(index)

    // ID3v2.3 TCON: "(17)", "(17)Indie" (refinement text wins), "(RX)" remix, "(CR)" cover.
    private val PAREN_FORM = Regex("""^\((\d{1,3}|RX|CR)\)(.*)$""")

    /** Display name for a raw genre tag: numeric forms resolved, whitespace trimmed; null when
     *  blank or an unknown number. Plain text passes through unchanged. */
    fun resolveDisplay(raw: String?): String? {
        val s = raw?.trim()?.ifBlank { null } ?: return null
        PAREN_FORM.find(s)?.let { m ->
            val text = m.groupValues[2].trim()
            if (text.isNotEmpty()) return text
            return when (val code = m.groupValues[1]) {
                "RX" -> "Remix"
                "CR" -> "Cover"
                else -> id3v1Name(code.toInt())
            }
        }
        if (s.length <= 3 && s.all { it.isDigit() }) return id3v1Name(s.toInt())
        return s
    }

    // Tags taggers write when they have nothing real to say; matching on them would link
    // unrelated tracks.
    private val PLACEHOLDERS = setOf("other", "unknown", "genre", "misc", "none")

    /** Normalized match keys: split on ; / , NUL, resolve numeric forms, lowercase, keep only
     *  letters/digits, drop placeholders. "Hip-Hop", "hip hop" and "HipHop" all give "hiphop". */
    fun keysOf(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(';', '/', ',', '\u0000')
            .mapNotNull { part ->
                resolveDisplay(part)?.lowercase()?.filter { it.isLetterOrDigit() }?.ifEmpty { null }
            }
            .filterNot { it in PLACEHOLDERS }
            .toSet()
    }
}
