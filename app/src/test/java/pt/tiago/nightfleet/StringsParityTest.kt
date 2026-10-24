package pt.tiago.nightfleet

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Keeps values/strings.xml (English) and values-pt/strings.xml (European Portuguese) in step:
 * every translatable key exists in both, and placeholders (%1$s, %d, ...) match for each key.
 * Keys marked translatable="false" (the brand) are excluded.
 */
class StringsParityTest {

    private val english = parseResources(File("src/main/res/values/strings.xml"))
    private val portuguese = parseResources(File("src/main/res/values-pt/strings.xml"))

    @Test
    fun everyTranslatableKeyExistsInBothLocales() {
        val missingInPortuguese = english.keys - portuguese.keys
        val missingInEnglish = portuguese.keys - english.keys
        assertTrue(
            "Keys missing in values-pt: $missingInPortuguese; keys missing in values: $missingInEnglish",
            missingInPortuguese.isEmpty() && missingInEnglish.isEmpty(),
        )
    }

    @Test
    fun placeholdersMatchForEveryKey() {
        val mismatches = english.keys.intersect(portuguese.keys).mapNotNull { key ->
            val en = placeholders(english.getValue(key))
            val pt = placeholders(portuguese.getValue(key))
            if (en == pt) null else "$key: en=$en pt=$pt"
        }
        assertTrue("Placeholder mismatches:\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }

    @Test
    fun pluralsExistInBothLocalesWithSamePlaceholders() {
        val enPlurals = parsePlurals(File("src/main/res/values/strings.xml"))
        val ptPlurals = parsePlurals(File("src/main/res/values-pt/strings.xml"))
        assertTrue("Plural keys differ: en=${enPlurals.keys} pt=${ptPlurals.keys}", enPlurals.keys == ptPlurals.keys)
        for ((name, enItems) in enPlurals) {
            val ptItems = ptPlurals.getValue(name)
            val enPlaceholders = enItems.values.map(::placeholders).toSet()
            val ptPlaceholders = ptItems.values.map(::placeholders).toSet()
            assertTrue("Plural $name: placeholders differ en=$enPlaceholders pt=$ptPlaceholders", enPlaceholders == ptPlaceholders)
        }
    }

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    /** Translatable strings only, keyed by name. */
    private fun parseResources(file: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (element in elements(file, "string")) {
            if (element.getAttribute("translatable") == "false") continue
            result[element.getAttribute("name")] = element.textContent
        }
        return result
    }

    /** Plural name to (quantity to text), translatable plurals only. */
    private fun parsePlurals(file: File): Map<String, Map<String, String>> {
        val result = linkedMapOf<String, Map<String, String>>()
        for (plural in elements(file, "plurals")) {
            if (plural.getAttribute("translatable") == "false") continue
            val items = linkedMapOf<String, String>()
            val children = plural.getElementsByTagName("item")
            for (i in 0 until children.length) {
                val item = children.item(i) as Element
                items[item.getAttribute("quantity")] = item.textContent
            }
            result[plural.getAttribute("name")] = items
        }
        return result
    }

    private fun elements(file: File, tag: String): List<Element> {
        assertTrue("Missing resource file: ${file.absolutePath}", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private companion object {
        // Matches printf-style placeholders such as %s, %1$s, %2$d, %1$.2f.
        val PLACEHOLDER = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")
    }
}
