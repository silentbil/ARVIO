package com.arflix.tv.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

val LocalAppLanguage = staticCompositionLocalOf { "en-US" }

fun appLocale(languageTag: String): Locale {
    val normalized = languageTag.replace('_', '-')
    return Locale.forLanguageTag(normalized).takeUnless { it.language.isBlank() } ?: Locale.US
}

fun localizedAppContext(context: Context, languageTag: String): Context {
    val locale = appLocale(languageTag)
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.setLocales(LocaleList(locale))
    } else {
        @Suppress("DEPRECATION")
        config.setLocale(locale)
    }
    return context.createConfigurationContext(config)
}

@Composable
fun tr(text: String): String = AppTranslations.translate(text, LocalAppLanguage.current)

@Composable
fun trUpper(text: String): String = AppTranslations.translate(text, LocalAppLanguage.current)
    .uppercase(appLocale(LocalAppLanguage.current))

object AppTranslations {
    fun translate(text: String, languageTag: String): String {
        if (text.isBlank()) return text
        val language = appLocale(languageTag).language.lowercase(Locale.US)
        if (language == "en") return text
        val normalized = text.trim()
        translateDynamic(normalized, language)?.let { return it }
        val table = translations[language] ?: return text
        return table[normalized] ?: text
    }

    private fun translateDynamic(text: String, language: String): String? {
        Regex("""Movies \((\d+)\)""").matchEntire(text)?.let {
            return "${translate("Movies", language)} (${it.groupValues[1]})"
        }
        Regex("""TV Shows \((\d+)\)""").matchEntire(text)?.let {
            return "${translate("TV Shows", language)} (${it.groupValues[1]})"
        }
        Regex("No results found for \"(.+)\"").matchEntire(text)?.let {
            return when (language) {
                "nl" -> "Geen resultaten gevonden voor \"${it.groupValues[1]}\""
                "de" -> "Keine Ergebnisse gefunden fuer \"${it.groupValues[1]}\""
                "fr" -> "Aucun resultat trouve pour \"${it.groupValues[1]}\""
                "es" -> "No se encontraron resultados para \"${it.groupValues[1]}\""
                "pt" -> "Nenhum resultado encontrado para \"${it.groupValues[1]}\""
                "it" -> "Nessun risultato trovato per \"${it.groupValues[1]}\""
                "tr" -> "\"${it.groupValues[1]}\" icin sonuc bulunamadi"
                "pl" -> "Nie znaleziono wynikow dla \"${it.groupValues[1]}\""
                else -> null
            }
        }
        Regex("""(\d+) sources available""").matchEntire(text)?.let {
            return when (language) {
                "nl" -> "${it.groupValues[1]} bronnen beschikbaar"
                "de" -> "${it.groupValues[1]} Quellen verfuegbar"
                "fr" -> "${it.groupValues[1]} sources disponibles"
                "es" -> "${it.groupValues[1]} fuentes disponibles"
                "pt" -> "${it.groupValues[1]} fontes disponiveis"
                "it" -> "${it.groupValues[1]} fonti disponibili"
                "tr" -> "${it.groupValues[1]} kaynak mevcut"
                "pl" -> "${it.groupValues[1]} dostepnych zrodel"
                else -> null
            }
        }
        Regex("""Next: (.+)""").matchEntire(text)?.let {
            return "${translate("Next", language)}: ${it.groupValues[1]}"
        }
        Regex("""Ends at (.+)""").matchEntire(text)?.let {
            return when (language) {
                "nl" -> "Eindigt om ${it.groupValues[1]}"
                "de" -> "Endet um ${it.groupValues[1]}"
                "fr" -> "Se termine a ${it.groupValues[1]}"
                "es" -> "Termina a las ${it.groupValues[1]}"
                "pt" -> "Termina as ${it.groupValues[1]}"
                "it" -> "Finisce alle ${it.groupValues[1]}"
                "tr" -> "${it.groupValues[1]} saatinde biter"
                "pl" -> "Konczy sie o ${it.groupValues[1]}"
                else -> null
            }
        }
        return null
    }

    private val translations: Map<String, Map<String, String>> = mapOf(
        "nl" to mapOf(
            "Home" to "Start",
            "Search" to "Zoeken",
            "Watchlist" to "Kijklijst",
            "TV" to "TV",
            "Settings" to "Instellingen",
            "General" to "Algemeen",
            "IPTV" to "IPTV",
            "Catalogs" to "Catalogi",
            "Stremio" to "Stremio",
            "Cloudstream" to "Cloudstream",
            "Accounts" to "Accounts",
            "MY WATCHLIST" to "MIJN KIJKLIJST",
            "Your watchlist is empty" to "Je kijklijst is leeg",
            "Add movies and shows to watch later" to "Voeg films en series toe om later te kijken",
            "Movies" to "Films",
            "Movie" to "Film",
            "TV Shows" to "Series",
            "Shows" to "Series",
            "Series" to "Serie",
            "All Genres" to "Alle genres",
            "Any Language" to "Elke taal",
            "Search or discover... try \"top 10 horror movies\"" to "Zoek of ontdek... probeer \"top 10 horrorfilms\"",
            "No results found" to "Geen resultaten gevonden",
            "Language & Subtitles" to "Taal en ondertitels",
            "Iptv" to "IPTV",
            "General" to "Algemeen",
            "Cloudstream" to "Cloudstream",
            "Stremio" to "Stremio",
            "App Language" to "App-taal",
            "Content Language" to "App-taal",
            "App text, titles, descriptions and metadata" to "App-tekst, titels, beschrijvingen en metadata",
            "Titles, descriptions and metadata" to "Titels, beschrijvingen en metadata",
            "Default Subtitle" to "Standaard ondertiteling",
            "Default Subtitles" to "Standaard ondertitels",
            "Auto-select subtitle language" to "Ondertiteltaal automatisch kiezen",
            "Default Audio" to "Standaard audio",
            "Preferred audio track" to "Voorkeurs-audiospoor",
            "Subtitle Size" to "Ondertitelgrootte",
            "Text size for subtitles" to "Tekstgrootte voor ondertitels",
            "Subtitle Color" to "Ondertitelkleur",
            "Text color for subtitles" to "Tekstkleur voor ondertitels",
            "Playback" to "Afspelen",
            "Auto-Play Next" to "Volgende automatisch afspelen",
            "Start next episode automatically" to "Start de volgende aflevering automatisch",
            "Autoplay" to "Automatisch afspelen",
            "Off opens the source picker on Play" to "Uit opent de bronkiezer bij Afspelen",
            "Auto-Play Min Quality" to "Minimale autoplay-kwaliteit",
            "Min quality for auto-play" to "Minimale kwaliteit voor autoplay",
            "Trailer Auto-Play" to "Trailers automatisch afspelen",
            "Play trailers in hero banner" to "Speel trailers af in de hero-banner",
            "Match Frame Rate" to "Framerate matchen",
            "Off, Seamless, or Always" to "Uit, naadloos of altijd",
            "Quality Regex Filters" to "Kwaliteitsfilters",
            "Exclude quality tiers on this device" to "Sluit kwaliteitsniveaus uit op dit apparaat",
            "Interface" to "Interface",
            "Card Layout" to "Kaartindeling",
            "Landscape or poster cards" to "Liggende of posterkaarten",
            "UI Mode" to "UI-modus",
            "Force TV, Tablet, or Phone" to "Forceer TV, tablet of telefoon",
            "Skip Profile Selection" to "Profielkeuze overslaan",
            "Auto-load last used profile" to "Laad automatisch het laatst gebruikte profiel",
            "Clock Format" to "Klokformaat",
            "Choose 12-hour or 24-hour time" to "Kies 12- of 24-uurs tijd",
            "Show Budget on Home" to "Budget tonen op start",
            "Display the movie budget on the home hero banner" to "Toon het filmbudget in de hero-banner",
            "Network" to "Netwerk",
            "DNS Provider" to "DNS-provider",
            "Resolve API and stream requests" to "Los API- en streamverzoeken op",
            "Audio" to "Audio",
            "Volume Boost" to "Volumeversterking",
            "Amplify quiet sources (via system LoudnessEnhancer)" to "Versterk zachte bronnen via systeemaudio",
            "Add Playlist" to "Afspeellijst toevoegen",
            "Add up to 3 M3U / Xtream IPTV lists with names" to "Voeg tot 3 M3U/Xtream IPTV-lijsten met namen toe",
            "Create another IPTV list" to "Maak nog een IPTV-lijst",
            "Refresh IPTV Data" to "IPTV-gegevens vernieuwen",
            "Refreshing channels and EPG..." to "Kanalen en EPG vernieuwen...",
            "Reload playlists now" to "Afspeellijsten nu herladen",
            "Reload playlist and EPG now" to "Afspeellijst en EPG nu herladen",
            "Delete IPTV Playlists" to "IPTV-afspeellijsten verwijderen",
            "No playlists configured" to "Geen afspeellijsten ingesteld",
            "Remove playlists, EPG and favorites" to "Verwijder afspeellijsten, EPG en favorieten",
            "Add Catalog" to "Catalogus toevoegen",
            "Import a Trakt or MDBList catalog URL" to "Importeer een Trakt- of MDBList-catalogus-URL",
            "Trakt/MDBList URLs can be added manually. Addon catalogs appear automatically." to "Trakt/MDBList-URL's kunnen handmatig worden toegevoegd. Addon-catalogi verschijnen automatisch.",
            "Linked Accounts" to "Gekoppelde accounts",
            "ARVIO Cloud" to "ARVIO Cloud",
            "Optional account for syncing profiles, addons, catalogs and IPTV settings" to "Optioneel account voor synchronisatie van profielen, addons, catalogi en IPTV-instellingen",
            "App Update" to "App-update",
            "App Updates" to "App-updates",
            "Unable to load content" to "Kan inhoud niet laden",
            "Retry" to "Opnieuw proberen",
            "In Cinema" to "In de bioscoop",
            "Details" to "Details",
            "Included with Prime" to "Inbegrepen bij Prime",
            "View Details" to "Details bekijken",
            "Add to Watchlist" to "Aan kijklijst toevoegen",
            "Remove from Watchlist" to "Uit kijklijst verwijderen",
            "Mark as Watched" to "Markeer als bekeken",
            "Mark as Unwatched" to "Markeer als niet bekeken",
            "Remove from Continue Watching" to "Uit Verder kijken verwijderen",
            "Press BACK to close" to "Druk op TERUG om te sluiten",
            "Trailer" to "Trailer",
            "Close trailer" to "Trailer sluiten",
            "Seasons" to "Seizoenen",
            "Season" to "Seizoen",
            "Episodes" to "Afleveringen",
            "Reviews" to "Recensies",
            "Budget" to "Budget",
            "BUDGET" to "BUDGET",
            "ONGOING" to "LOPEND",
            "Sources" to "Bronnen",
            "FILTER BY SOURCE" to "FILTER OP BRON",
            "Available Sources" to "Beschikbare bronnen",
            "Finding sources..." to "Bronnen zoeken...",
            "Close" to "Sluiten",
            "Selected" to "Geselecteerd",
            "Play" to "Afspelen",
            "Subtitles" to "Ondertitels",
            "Subtitles & Audio" to "Ondertitels en audio",
            "Audio Track" to "Audiospoor",
            "Next Episode" to "Volgende aflevering",
            "Switch tabs • Navigate • BACK Close" to "Wissel tabs • Navigeer • TERUG sluiten",
            "ARVIO uses community streaming addons to find video sources. Without at least one streaming addon, content cannot be played." to "ARVIO gebruikt streaming-addons uit de community om videobronnen te vinden. Zonder minimaal een streaming-addon kan inhoud niet worden afgespeeld.",
            "No audio tracks available" to "Geen audiosporen beschikbaar",
            "TRY AGAIN" to "OPNIEUW PROBEREN",
            "GO BACK" to "TERUG",
            "UP NEXT" to "HIERNA",
            "PLAY NOW" to "NU AFSPELEN",
            "CANCEL" to "ANNULEREN",
            "OK" to "OK",
            "NOW" to "NU",
            "NEXT" to "VOLGENDE",
            "Next" to "Volgende",
            "LATER" to "LATER",
            "LIVE" to "LIVE",
            "IPTV is not configured" to "IPTV is niet ingesteld",
            "Back to channel list" to "Terug naar kanaallijst",
            "Previous channel" to "Vorig kanaal",
            "Next channel" to "Volgend kanaal",
            "Off" to "Uit",
            "On" to "Aan",
            "Auto" to "Auto",
            "Tablet" to "Tablet",
            "Phone" to "Telefoon",
            "Landscape" to "Liggend",
            "Poster" to "Poster",
            "Medium" to "Middel",
            "White" to "Wit",
            "Yellow" to "Geel",
            "Green" to "Groen",
            "Cyan" to "Cyaan",
            "ADD" to "TOEVOEGEN",
            "FULL" to "VOL",
            "LOADING" to "LADEN",
            "REFRESH" to "VERNIEUWEN",
            "EMPTY" to "LEEG",
            "DELETE" to "VERWIJDEREN",
            "CONNECTED" to "VERBONDEN",
            "CONNECT" to "VERBINDEN",
            "Cancel" to "Annuleren",
            "Confirm" to "Bevestigen",
            "Create" to "Maken",
            "Remove" to "Verwijderen",
            "Email" to "E-mail",
            "Password" to "Wachtwoord",
            "Sign In" to "Inloggen",
            "Enter code:" to "Voer code in:",
            "Waiting for authorization... (Press OK to cancel)" to "Wachten op autorisatie... (druk OK om te annuleren)",
            "Ends at" to "Eindigt om"
        ),
        "de" to commonWestern(
            home = "Start", search = "Suche", watchlist = "Merkliste", settings = "Einstellungen",
            movies = "Filme", shows = "Serien", appLanguage = "App-Sprache", playback = "Wiedergabe",
            sources = "Quellen", finding = "Quellen werden gesucht...", noResults = "Keine Ergebnisse gefunden",
            emptyWatchlist = "Deine Merkliste ist leer", addLater = "Filme und Serien fuer spaeter hinzufuegen",
            subtitles = "Untertitel", audio = "Audio", catalogs = "Kataloge", accounts = "Konten",
            close = "Schliessen", cancel = "Abbrechen", confirm = "Bestaetigen"
        ),
        "fr" to commonWestern(
            home = "Accueil", search = "Recherche", watchlist = "Liste", settings = "Parametres",
            movies = "Films", shows = "Series", appLanguage = "Langue de l'app", playback = "Lecture",
            sources = "Sources", finding = "Recherche des sources...", noResults = "Aucun resultat",
            emptyWatchlist = "Votre liste est vide", addLater = "Ajoutez des films et series a regarder plus tard",
            subtitles = "Sous-titres", audio = "Audio", catalogs = "Catalogues", accounts = "Comptes",
            close = "Fermer", cancel = "Annuler", confirm = "Confirmer"
        ),
        "es" to commonWestern(
            home = "Inicio", search = "Buscar", watchlist = "Lista", settings = "Ajustes",
            movies = "Peliculas", shows = "Series", appLanguage = "Idioma de la app", playback = "Reproduccion",
            sources = "Fuentes", finding = "Buscando fuentes...", noResults = "No hay resultados",
            emptyWatchlist = "Tu lista esta vacia", addLater = "Anade peliculas y series para ver mas tarde",
            subtitles = "Subtitulos", audio = "Audio", catalogs = "Catalogos", accounts = "Cuentas",
            close = "Cerrar", cancel = "Cancelar", confirm = "Confirmar"
        ),
        "pt" to commonWestern(
            home = "Inicio", search = "Pesquisar", watchlist = "Lista", settings = "Definicoes",
            movies = "Filmes", shows = "Series", appLanguage = "Idioma da app", playback = "Reproducao",
            sources = "Fontes", finding = "A procurar fontes...", noResults = "Sem resultados",
            emptyWatchlist = "A sua lista esta vazia", addLater = "Adicione filmes e series para ver mais tarde",
            subtitles = "Legendas", audio = "Audio", catalogs = "Catalogos", accounts = "Contas",
            close = "Fechar", cancel = "Cancelar", confirm = "Confirmar"
        ),
        "it" to commonWestern(
            home = "Home", search = "Cerca", watchlist = "Lista", settings = "Impostazioni",
            movies = "Film", shows = "Serie", appLanguage = "Lingua app", playback = "Riproduzione",
            sources = "Fonti", finding = "Ricerca fonti...", noResults = "Nessun risultato",
            emptyWatchlist = "La tua lista e vuota", addLater = "Aggiungi film e serie da guardare piu tardi",
            subtitles = "Sottotitoli", audio = "Audio", catalogs = "Cataloghi", accounts = "Account",
            close = "Chiudi", cancel = "Annulla", confirm = "Conferma"
        ),
        "tr" to commonWestern(
            home = "Ana sayfa", search = "Ara", watchlist = "Izleme listesi", settings = "Ayarlar",
            movies = "Filmler", shows = "Diziler", appLanguage = "Uygulama dili", playback = "Oynatma",
            sources = "Kaynaklar", finding = "Kaynaklar bulunuyor...", noResults = "Sonuc yok",
            emptyWatchlist = "Izleme listen bos", addLater = "Daha sonra izlemek icin film ve dizi ekle",
            subtitles = "Altyazilar", audio = "Ses", catalogs = "Kataloglar", accounts = "Hesaplar",
            close = "Kapat", cancel = "Iptal", confirm = "Onayla"
        ),
        "pl" to commonWestern(
            home = "Start", search = "Szukaj", watchlist = "Lista", settings = "Ustawienia",
            movies = "Filmy", shows = "Seriale", appLanguage = "Jezyk aplikacji", playback = "Odtwarzanie",
            sources = "Zrodla", finding = "Szukanie zrodel...", noResults = "Brak wynikow",
            emptyWatchlist = "Twoja lista jest pusta", addLater = "Dodaj filmy i seriale na pozniej",
            subtitles = "Napisy", audio = "Dzwiek", catalogs = "Katalogi", accounts = "Konta",
            close = "Zamknij", cancel = "Anuluj", confirm = "Potwierdz"
        )
    )

    private fun commonWestern(
        home: String,
        search: String,
        watchlist: String,
        settings: String,
        movies: String,
        shows: String,
        appLanguage: String,
        playback: String,
        sources: String,
        finding: String,
        noResults: String,
        emptyWatchlist: String,
        addLater: String,
        subtitles: String,
        audio: String,
        catalogs: String,
        accounts: String,
        close: String,
        cancel: String,
        confirm: String
    ): Map<String, String> = mapOf(
        "Home" to home,
        "Search" to search,
        "Watchlist" to watchlist,
        "Settings" to settings,
        "MY WATCHLIST" to watchlist.uppercase(Locale.US),
        "Your watchlist is empty" to emptyWatchlist,
        "Add movies and shows to watch later" to addLater,
        "Movies" to movies,
        "Movie" to movies.removeSuffix("s"),
        "TV Shows" to shows,
        "Shows" to shows,
        "Series" to shows,
        "All Genres" to "All",
        "Any Language" to appLanguage,
        "Search or discover... try \"top 10 horror movies\"" to search,
        "No results found" to noResults,
        "Language & Subtitles" to "$appLanguage / $subtitles",
        "Iptv" to "IPTV",
        "App Language" to appLanguage,
        "Content Language" to appLanguage,
        "App text, titles, descriptions and metadata" to appLanguage,
        "Titles, descriptions and metadata" to appLanguage,
        "Default Subtitle" to subtitles,
        "Default Subtitles" to subtitles,
        "Auto-select subtitle language" to subtitles,
        "Default Audio" to audio,
        "Preferred audio track" to audio,
        "Subtitle Size" to subtitles,
        "Text size for subtitles" to subtitles,
        "Subtitle Color" to subtitles,
        "Text color for subtitles" to subtitles,
        "Playback" to playback,
        "Auto-Play Next" to playback,
        "Start next episode automatically" to playback,
        "Autoplay" to playback,
        "Off opens the source picker on Play" to sources,
        "Auto-Play Min Quality" to playback,
        "Min quality for auto-play" to playback,
        "Trailer Auto-Play" to playback,
        "Play trailers in hero banner" to playback,
        "Match Frame Rate" to playback,
        "Interface" to "Interface",
        "Network" to "Network",
        "Audio" to audio,
        "Audio Track" to audio,
        "IPTV" to "IPTV",
        "Catalogs" to catalogs,
        "Linked Accounts" to accounts,
        "Accounts" to accounts,
        "Sources" to sources,
        "FILTER BY SOURCE" to sources.uppercase(Locale.US),
        "Available Sources" to sources,
        "Finding sources..." to finding,
        "Close" to close,
        "Selected" to confirm,
        "Details" to confirm,
        "View Details" to confirm,
        "Add to Watchlist" to watchlist,
        "Remove from Watchlist" to watchlist,
        "Play" to playback,
        "Subtitles" to subtitles,
        "Subtitles & Audio" to "$subtitles / $audio",
        "No audio tracks available" to audio,
        "TRY AGAIN" to noResults.uppercase(Locale.US),
        "GO BACK" to close.uppercase(Locale.US),
        "NOW" to "NOW",
        "NEXT" to "NEXT",
        "Next" to "Next",
        "Next Episode" to "Next",
        "LATER" to "LATER",
        "LIVE" to "LIVE",
        "Off" to "Off",
        "On" to "On",
        "Auto" to "Auto",
        "ADD" to "ADD",
        "FULL" to "FULL",
        "LOADING" to "LOADING",
        "REFRESH" to "REFRESH",
        "EMPTY" to "EMPTY",
        "DELETE" to "DELETE",
        "Cancel" to cancel,
        "Confirm" to confirm,
        "Create" to confirm,
        "Remove" to close,
        "Email" to "Email",
        "Password" to "Password",
        "Sign In" to confirm
    )
}
