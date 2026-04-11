package test.testkit.parser

public data class GradleTask(val name: String, val description: String? = null)
public data class TaskCategory(val name: String, val tasks: List<GradleTask>)
public data class GradleRule(val pattern: String, val description: String)
public data class GradleOutput(val categories: List<TaskCategory>, val rules: List<GradleRule>)

private sealed interface LineType
private data class CategoryHeader(val name: String) : LineType
private object RulesHeader : LineType
private object Divider : LineType
private data class Content(val text: String) : LineType
private object EmptyLine : LineType

public fun parseGradleTasksOutput(output: String): GradleOutput {

    fun normalizeCategoryName(categoryLine: String): String =
        categoryLine.replace(" tasks", "").lowercase()

    fun String.classify(): LineType = when {
        isBlank() -> EmptyLine
        trim().contains(" tasks") -> CategoryHeader(normalizeCategoryName(trim()))
        trim() == "Rules" -> RulesHeader
        trim().isNotEmpty() && trim().all { it == '-' } -> Divider
        else -> Content(trim())
    }

    val lineTypes = output.lineSequence().map { it.classify() }

    val sections = lineTypes
        .windowed(3, 1, partialWindows = true)
        .fold(Pair(emptyList<List<LineType>>(), mutableListOf<LineType>())) { (sections, currentSection), window ->
            val isSectionStart = window.size >= 2 &&
                    (window[0] is CategoryHeader || window[0] is RulesHeader) &&
                    window[1] is Divider

            if (isSectionStart && currentSection.isNotEmpty()) {
                Pair(sections + listOf(currentSection.toList()), mutableListOf<LineType>().apply { addAll(window) })
            } else {
                currentSection.addAll(window.drop(if (currentSection.isEmpty()) 0 else window.size - 1))
                Pair(sections, currentSection)
            }
        }
        .let { (sections, currentSection) ->
            if (currentSection.isNotEmpty()) sections + listOf(currentSection) else sections
        }

    val categories = sections
        .filter { it.isNotEmpty() && it.first() is CategoryHeader }
        .mapNotNull { section ->
            val header = section.first() as CategoryHeader
            val tasks = section
                .dropWhile { it !is Content }
                .takeWhile { it is Content }
                .mapNotNull {
                    if (it is Content) {
                        val parts = it.text.split(" - ", limit = 2)
                        if (parts.size == 2) {
                            GradleTask(parts[0].trim(), parts[1].trim())
                        } else {
                            GradleTask(it.text)
                        }
                    } else null
                }

            if (tasks.isNotEmpty()) TaskCategory(header.name, tasks) else null
        }

    val rules = sections
        .find { it.isNotEmpty() && it.first() is RulesHeader }
        ?.dropWhile { it !is Content }
        ?.takeWhile { it is Content && it.text.contains("Pattern:") }
        ?.mapNotNull { line ->
            if (line is Content && line.text.startsWith("Pattern:")) {
                val parts = line.text.removePrefix("Pattern:").split(":", limit = 2)
                if (parts.size == 2) {
                    GradleRule(parts[0].trim(), parts[1].trim())
                } else null
            } else null
        } ?: emptyList()

    return GradleOutput(categories, rules)
}