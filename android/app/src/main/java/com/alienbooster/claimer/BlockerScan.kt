package com.alienbooster.claimer

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Dialogs that cover the CTA. Jump dialogs are dismissed with 拒绝, exit
 * confirms with 取消, and the shared "广告加载失败" state with 点击重试.
 * None of these are a credit or a done signal.
 */
object BlockerScan {
    data class Hit(val kind: String, val x: Int, val y: Int)

    private data class NodeText(val text: String, val desc: String, val bounds: Rect?)

    private val boundsRe = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")

    fun find(xml: String?): Hit? {
        if (xml.isNullOrBlank()) return null
        val nodes = read(xml)
        if (nodes.isEmpty()) return null
        val blob = nodes.joinToString("\n") { it.text + "\n" + it.desc }

        if (blob.contains("拒绝") && blob.contains("允许")) {
            val deny = nodes.firstOrNull { it.text == "拒绝" && it.bounds != null }
            if (deny != null) {
                return Hit("jump", deny.bounds!!.centerX, deny.bounds.centerY)
            }
        }
        if (blob.contains("确定要退出") || blob.contains("是否退出") || blob.contains("退出应用")) {
            val cancel = nodes.firstOrNull { it.text == "取消" && it.bounds != null }
            if (cancel != null) {
                return Hit("exit", cancel.bounds!!.centerX, cancel.bounds.centerY)
            }
        }
        if (blob.contains("加载失败")) {
            val retry = nodes.firstOrNull {
                (it.text.contains("重试") || it.desc.contains("重试")) && it.bounds != null
            }
            if (retry != null) {
                return Hit("retry", retry.bounds!!.centerX, retry.bounds.centerY)
            }
        }
        if (blob.contains("权限") &&
            (blob.contains("拒绝") || blob.contains("不允许") || blob.contains("禁止"))
        ) {
            val deny = nodes.firstOrNull {
                (it.text == "拒绝" || it.text == "不允许" || it.text == "禁止") && it.bounds != null
            }
            if (deny != null) {
                return Hit("permission", deny.bounds!!.centerX, deny.bounds.centerY)
            }
        }
        return null
    }

    private fun read(xml: String): List<NodeText> {
        val body = slice(xml) ?: return emptyList()
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isExpandEntityReferences = false
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            } catch (_: Exception) {
            }
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
            }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
            val out = ArrayList<NodeText>()
            fun walk(el: Element) {
                val text = el.getAttribute("text").trim()
                val desc = el.getAttribute("content-desc").trim()
                if (text.isNotEmpty() || desc.isNotEmpty()) {
                    out.add(NodeText(text, desc, bounds(el.getAttribute("bounds"))))
                }
                val children = el.childNodes
                for (i in 0 until children.length) {
                    val child = children.item(i)
                    if (child is Element) walk(child)
                }
            }
            val root = doc.documentElement ?: return emptyList()
            walk(root)
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun slice(raw: String): String? {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.startsWith("<")) return text
        val decl = text.indexOf("<?xml")
        val tag = text.indexOf("<hierarchy")
        val start = listOf(decl, tag).filter { it >= 0 }.minOrNull() ?: return null
        return text.substring(start)
    }

    private fun bounds(raw: String?): Rect? {
        if (raw.isNullOrBlank()) return null
        val match = boundsRe.find(raw) ?: return null
        return Rect(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt()
        )
    }
}
