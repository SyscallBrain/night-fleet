package pt.tiago.nightfleet.ui.tutorial

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The translatable strings of values/ (English) and values-pt/ (European Portuguese), keyed by name. */
internal object TestStrings {

    val english: Map<String, String> by lazy { load("src/main/res/values/strings.xml") }
    val portuguese: Map<String, String> by lazy { load("src/main/res/values-pt/strings.xml") }

    /** Words that belong to Brazilian Portuguese and must not appear in the pt-PT texts. */
    val forbiddenPortuguese: List<String> = listOf("tela", "configurações", "salvar", "celular", "usuário", "você")

    private fun load(path: String): Map<String, String> {
        val file = File(path)
        check(file.isFile) { "Missing resource file: ${file.absolutePath}" }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            if (element.getAttribute("translatable") == "false") continue
            result[element.getAttribute("name")] = element.textContent
        }
        return result
    }
}
