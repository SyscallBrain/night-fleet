package pt.tiago.nightfleet.ui.board

import pt.tiago.nightfleet.catalog.CatalogRepository
import pt.tiago.nightfleet.catalog.CatalogResult

/**
 * The weather drawn for a campaign sea, from the `weather` id of its `seas/sNN.json`.
 * An id this build does not know is calm: a newer content file never breaks the board.
 */
fun seaWeather(weatherId: String): WeatherKind = when (weatherId) {
    "calm" -> WeatherKind.CALM
    "light-haze" -> WeatherKind.FOG
    "sunset-gulls" -> WeatherKind.GULLS
    "aurora" -> WeatherKind.AURORA
    "moonless-night" -> WeatherKind.STARS
    "full-moon" -> WeatherKind.MOON
    "light-rain" -> WeatherKind.RAIN
    "green-fog" -> WeatherKind.ALGAE
    "snow" -> WeatherKind.SNOW
    "storm" -> WeatherKind.STORM
    "deep-sea" -> WeatherKind.DEEP
    "clear-stars" -> WeatherKind.STARS
    else -> WeatherKind.CALM
}

/** The weather of sea [sea] (1..12). A sea whose metadata cannot be read has calm weather. */
fun seaWeatherOf(catalog: CatalogRepository, sea: Int): WeatherKind =
    (catalog.sea(sea) as? CatalogResult.Ok)?.value?.weather?.let(::seaWeather) ?: WeatherKind.CALM
