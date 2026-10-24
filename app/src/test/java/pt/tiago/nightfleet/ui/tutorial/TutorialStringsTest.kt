package pt.tiago.nightfleet.ui.tutorial

import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.ui.intro.MechanicIntro

class TutorialStringsTest {

    private val en = TestStrings.english
    private val pt = TestStrings.portuguese

    private val tutorialKeys: List<String>
        get() = TutorialScripts.missions.flatMap { script -> script.steps.map { it.textKey } }

    private val overlayKeys = listOf(
        "tutorial_skip",
        "tutorial_tap_to_continue",
        "tutorial_skip_title",
        "tutorial_skip_body",
        "tutorial_skip_confirm",
        "tutorial_skip_keep",
    )

    @Test
    fun everyTutorialStepTextExistsInBothLanguages() {
        val keys = tutorialKeys + overlayKeys
        val missingEnglish = keys.filter { en[it].isNullOrBlank() }
        val missingPortuguese = keys.filter { pt[it].isNullOrBlank() }
        assertTrue("Missing in values/: $missingEnglish", missingEnglish.isEmpty())
        assertTrue("Missing in values-pt/: $missingPortuguese", missingPortuguese.isEmpty())
    }

    @Test
    fun everyMechanicCardKeyExistsInBothLanguages() {
        val keys = MechanicIntro.entries.flatMap { it.stringKeys } + "intro_got_it" + "intro_kicker" + "intro_manual_title"
        assertTrue("Missing in values/: ${keys.filter { en[it].isNullOrBlank() }}", keys.all { !en[it].isNullOrBlank() })
        assertTrue("Missing in values-pt/: ${keys.filter { pt[it].isNullOrBlank() }}", keys.all { !pt[it].isNullOrBlank() })
    }

    @Test
    fun thereAreSeventeenMechanicCardsWithDistinctTags() {
        assertTrue(MechanicIntro.entries.size == 17)
        assertTrue(MechanicIntro.entries.map { it.tag }.distinct().size == 17)
    }

    @Test
    fun portugueseTextsDoNotUseBrazilianWords() {
        val checked = (tutorialKeys + overlayKeys + MechanicIntro.entries.flatMap { it.stringKeys }).distinct()
        val offenders = checked.flatMap { key ->
            val text = pt[key].orEmpty().lowercase()
            TestStrings.forbiddenPortuguese.filter { Regex("(?<!\\p{L})" + Regex.escape(it) + "(?!\\p{L})").containsMatchIn(text) }
                .map { "$key contains '$it'" }
        }
        assertTrue("Brazilian words: $offenders", offenders.isEmpty())
    }

    @Test
    fun noPortugueseStringInTheFileUsesBrazilianWords() {
        val offenders = pt.flatMap { (key, value) ->
            val text = value.lowercase()
            TestStrings.forbiddenPortuguese.filter { text.contains(it) }.map { "$key contains '$it'" }
        }
        assertTrue("Brazilian words: $offenders", offenders.isEmpty())
    }

    @Test
    fun everyCardHasTwoSentencesInBothLanguages() {
        val sentenceEnd = Regex("[.!?](?=\\s|$)")
        for (mechanic in MechanicIntro.entries) {
            for (key in listOf(mechanic.firstSentenceKey, mechanic.secondSentenceKey)) {
                for ((language, texts) in listOf("en" to en, "pt" to pt)) {
                    val text = texts.getValue(key).trim()
                    assertTrue("$language $key is not one sentence: $text", sentenceEnd.findAll(text).count() == 1)
                    assertTrue("$language $key does not end a sentence: $text", sentenceEnd.containsMatchIn(text.takeLast(1)))
                }
            }
        }
    }

    @Test
    fun tutorialTextsStayShort() {
        for (key in tutorialKeys) {
            assertTrue("$key is too long for a bubble", en.getValue(key).length <= 120)
            assertTrue("$key (pt) is too long for a bubble", pt.getValue(key).length <= 140)
        }
    }
}
