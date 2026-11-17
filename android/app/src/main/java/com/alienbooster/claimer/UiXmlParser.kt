package com.alienbooster.claimer

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

class UiState(
    val stageCompleted: IntArray = IntArray(3),
    val stageMax: IntArray = intArrayOf(9, 3, 9),
    val stageStatus: List<String> = emptyList(),
    val durationSec: Int? = null,
    val ctaText: String? = null,
    val ctaBounds: Rect? = null,
    val done: Boolean = false,
    val login: Boolean = false,
    val mainVisible: Boolean = false,
    val userName: String? = null,
    val userId: String? = null,
    val rawText: String = ""
) {
    fun stageTotal(): Int = stageCompleted.sum()

    override fun toString(): String {
        return "stages=${stageCompleted.toList()}/${stageMax.toList()} status=$stageStatus " +
            "dur=$durationSec cta=$ctaText done=$done login=$login main=$mainVisible " +
            "user=$userName/$userId"
    }
}

object UiXmlParser {
    private val COUNT_RE = Regex("""(\d+)\s*/\s*(\d+)""")
    private val BOUNDS_RE = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    private val DEFAULT_MAX = intArrayOf(9, 3, 9)
    private val TITLE_MAX = mapOf("阶段一" to 9, "阶段二" to 3, "加油包" to 9)
    private val CTA_HINTS = listOf("领时长", "看广告", "点广告", "点击重试", "立即领取")

    fun parse(xml: String?): UiState {
        if (xml.isNullOrBlank()) return UiState()
        val body = sliceXml(xml) ?: return UiState()
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isExpandEntityReferences = false
            setQuiet(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setQuiet(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(body)))
            read(doc.documentElement)
        } catch (_: Throwable) {
            UiState()
        }
    }

    private fun sliceXml(raw: String): String? {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.startsWith("<")) return text
        val decl = text.indexOf("<?xml")
        val tag = text.indexOf("<hierarchy")
        val start = listOf(decl, tag).filter { it >= 0 }.minOrNull() ?: return null
        return text.substring(start).trim()
    }

    private fun read(root: Element?): UiState {
        if (root == null) return UiState()
        val elements = descendants(root)
        val completed = IntArray(3)
        val max = intArrayOf(9, 3, 9)
        val status = ArrayList<String>()

        val blocks = elements.filter { idEnds(it, "UIBG") }.map { blockRoot(it) }
        if (blocks.isNotEmpty()) {
            val limit = minOf(3, blocks.size)
            for (i in 0 until limit) {
                val block = blocks[i]
                val title = textIn(block, "UITitle", nonBlank = true)
                val state = textIn(block, "UIStateTitle", nonBlank = false) ?: ""
                applyStage(i, state, title, completed, max, status)
            }
        } else {
            val titles = elements.filter {
                idEnds(it, "UITitle") && it.getAttribute("text").trim().isNotEmpty()
            }
            val states = elements.filter { idEnds(it, "UIStateTitle") }
            val n = minOf(3, maxOf(titles.size, states.size))
            for (i in 0 until n) {
                val title = titles.getOrNull(i)?.getAttribute("text")?.trim()
                val state = states.getOrNull(i)?.getAttribute("text")?.trim().orEmpty()
                applyStage(i, state, title, completed, max, status)
            }
        }

        val hour = intById(elements, "UIPCDurationHourTxt")
        val minute = intById(elements, "UIPCDurationMinuteTxt")
        val second = intById(elements, "UIPCDurationSecondTxt")
        val duration = if (hour != null && minute != null) {
            hour * 3600 + minute * 60 + (second ?: 0)
        } else {
            null
        }

        val cta = findCta(elements)
        val texts = ArrayList<String>()
        for (el in elements) {
            val text = el.getAttribute("text").trim()
            if (text.isNotEmpty()) texts.add(text)
            val desc = el.getAttribute("content-desc").trim()
            if (desc.isNotEmpty()) texts.add(desc)
        }
        val raw = texts.joinToString("\n")
        val hasTaskList = elements.any { idEnds(it, "UIPCADTaskList") }
        val titleCount = elements.count { idEnds(it, "UITitle") }
        val hasStageList = hasTaskList || status.isNotEmpty()
        val userName = textOfFirst(elements, "UIUserName")
        val userId = textOfFirst(elements, "UIUserID")
        return UiState(
            stageCompleted = completed,
            stageMax = max,
            stageStatus = status,
            durationSec = duration,
            ctaText = cta?.first,
            ctaBounds = cta?.second,
            done = RewardPolicy.isDone(raw, cta?.first, completed, max),
            login = RewardPolicy.isLogin(raw, hasStageList),
            mainVisible = hasTaskList || titleCount >= 3,
            userName = userName,
            userId = userId,
            rawText = raw
        )
    }

    private fun applyStage(
        index: Int,
        stateText: String,
        title: String?,
        completed: IntArray,
        max: IntArray,
        status: MutableList<String>
    ) {
        if (index !in 0..2) return
        val fraction = COUNT_RE.find(stateText)
        val stageMax = when {
            fraction != null -> fraction.groupValues[2].toInt()
            title != null && TITLE_MAX.containsKey(title) -> TITLE_MAX.getValue(title)
            else -> DEFAULT_MAX[index]
        }
        val stageCompleted = when {
            fraction != null -> fraction.groupValues[1].toInt()
            stateText.contains("已完成") -> stageMax
            stateText.contains("待解锁") -> 0
            else -> 0
        }
        completed[index] = stageCompleted
        max[index] = stageMax
        status.add(stateText)
    }

    private fun findCta(elements: List<Element>): Pair<String, Rect?>? {
        val labeled = elements.filter { idEnds(it, "UIADSubmitText") }
        val primary = labeled
            .map { it.getAttribute("text").trim() to boundsOf(it) }
            .filter { it.first.isNotEmpty() }
            .maxByOrNull { it.second?.top ?: Int.MIN_VALUE }
        if (primary != null) return primary

        val fallback = elements.mapNotNull { el ->
            val text = el.getAttribute("text").trim()
            if (text.isEmpty() || CTA_HINTS.none { text.contains(it) }) return@mapNotNull null
            val bounds = boundsOf(el) ?: return@mapNotNull null
            text to bounds
        }.maxByOrNull { it.second?.top ?: Int.MIN_VALUE }
        return fallback
    }

    private fun textIn(root: Element, idName: String, nonBlank: Boolean): String? {
        if (idEnds(root, idName)) {
            val own = root.getAttribute("text").trim()
            if (!nonBlank || own.isNotEmpty()) return own
        }
        for (child in descendants(root)) {
            if (!idEnds(child, idName)) continue
            val text = child.getAttribute("text").trim()
            if (nonBlank && text.isEmpty()) continue
            return text
        }
        return null
    }

    private fun textOfFirst(elements: List<Element>, idName: String): String? {
        val text = elements.firstOrNull { idEnds(it, idName) }?.getAttribute("text")?.trim()
        return if (text.isNullOrEmpty()) null else text
    }

    private fun intById(elements: List<Element>, idName: String): Int? {
        val text = textOfFirst(elements, idName) ?: return null
        return text.toIntOrNull()
    }

    private fun blockRoot(uibg: Element): Element {
        if (childElements(uibg).isNotEmpty()) return uibg
        val parent = uibg.parentNode
        return if (parent is Element) parent else uibg
    }

    private fun childElements(el: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val child = nodes.item(i)
            if (child is Element) out.add(child)
        }
        return out
    }

    private fun descendants(root: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(el: Element) {
            val nodes = el.childNodes
            for (i in 0 until nodes.length) {
                val child = nodes.item(i)
                if (child is Element) {
                    out.add(child)
                    walk(child)
                }
            }
        }
        if (root.tagName == "node") out.add(root)
        walk(root)
        return out
    }

    private fun idEnds(el: Element, name: String): Boolean {
        val id = el.getAttribute("resource-id") ?: return false
        return id == name || id.endsWith("/$name")
    }

    private fun boundsOf(el: Element): Rect? {
        val raw = el.getAttribute("bounds") ?: return null
        val match = BOUNDS_RE.find(raw) ?: return null
        return Rect(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt()
        )
    }

    private fun setQuiet(factory: DocumentBuilderFactory, feature: String, value: Boolean) {
        try {
            factory.setFeature(feature, value)
        } catch (_: Exception) {
        }
    }

}
