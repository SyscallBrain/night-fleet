package pt.tiago.nightfleet.ui.board

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every weather id of the campaign seas (content/seas) maps to a drawn weather; an unknown id is calm. */
class SeaWeatherTest {

    @Test
    fun campaignSeasMapToTheirWeather() {
        val expected = mapOf(
            "calm" to WeatherKind.CALM,
            "light-haze" to WeatherKind.FOG,
            "sunset-gulls" to WeatherKind.GULLS,
            "aurora" to WeatherKind.AURORA,
            "moonless-night" to WeatherKind.STARS,
            "full-moon" to WeatherKind.MOON,
            "light-rain" to WeatherKind.RAIN,
            "green-fog" to WeatherKind.ALGAE,
            "snow" to WeatherKind.SNOW,
            "storm" to WeatherKind.STORM,
            "deep-sea" to WeatherKind.DEEP,
            "clear-stars" to WeatherKind.STARS,
        )
        for ((id, kind) in expected) assertEquals(id, kind, seaWeather(id))
    }

    @Test
    fun unknownOrEmptyIdsAreCalm() {
        assertEquals(WeatherKind.CALM, seaWeather("hurricane"))
        assertEquals(WeatherKind.CALM, seaWeather(""))
    }
}
