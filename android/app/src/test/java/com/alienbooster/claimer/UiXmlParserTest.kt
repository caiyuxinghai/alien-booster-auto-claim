package com.alienbooster.claimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiXmlParserTest {
    @Test
    fun partialMainScreen() {
        val state = UiXmlParser.parse(load("main_partial.xml"))
        assertEquals(5, state.stageCompleted[0])
        assertEquals(9, state.stageMax[0])
        assertEquals(0, state.stageCompleted[1])
        assertEquals(3, state.stageMax[1])
        assertEquals(0, state.stageCompleted[2])
        assertEquals(9, state.stageMax[2])
        assertEquals(listOf("5 / 9", "待解锁", "待解锁"), state.stageStatus)
        assertEquals(491 * 3600 + 45 * 60 + 7, state.durationSec)
        assertEquals("看广告 领时长", state.ctaText)
        assertEquals(Rect(482, 2229, 789, 2295), state.ctaBounds)
        assertEquals("用户昵称", state.userName)
        assertEquals("UID:1000000", state.userId)
        assertTrue(state.mainVisible)
        assertFalse(state.done)
        assertFalse(state.login)
    }

    @Test
    fun doneMainScreen() {
        val state = UiXmlParser.parse(load("main_done.xml"))
        assertEquals(9, state.stageCompleted[0])
        assertEquals(3, state.stageCompleted[1])
        assertEquals(9, state.stageCompleted[2])
        assertTrue(state.stageCompleted.contentEquals(state.stageMax))
        assertEquals(listOf("已完成", "已完成", "已完成"), state.stageStatus)
        assertEquals(502 * 3600 + 5 * 60 + 7, state.durationSec)
        assertEquals("今日广告已看完，请明日再来", state.ctaText)
        assertEquals(Rect(317, 2229, 954, 2295), state.ctaBounds)
        assertEquals("用户昵称", state.userName)
        assertEquals("UID:1000000", state.userId)
        assertTrue(state.mainVisible)
        assertTrue(state.done)
        assertFalse(state.login)
    }

    @Test
    fun emptyAndMalformedDoNotCrash() {
        val samples = listOf(null, "", "   ", "<", "<hierarchy>", "<?xml version='1.0'?><notclosed>", "\u0000nope")
        for (xml in samples) {
            val state = UiXmlParser.parse(xml)
            assertNotNull(state)
            assertFalse(state.done)
            assertFalse(state.login)
            assertNull(state.ctaText)
        }
    }

    @Test
    fun pairsTitlesWhenUibgIsMissing() {
        val xml = """
            <hierarchy>
              <node resource-id="com.etalien.booster:id/UITitle" text="阶段一" />
              <node resource-id="com.etalien.booster:id/UIStateTitle" text="2 / 9" />
              <node resource-id="com.etalien.booster:id/UITitle" text="阶段二" />
              <node resource-id="com.etalien.booster:id/UIStateTitle" text="已完成" />
              <node resource-id="com.etalien.booster:id/UITitle" text="加油包" />
              <node resource-id="com.etalien.booster:id/UIStateTitle" text="待解锁" />
            </hierarchy>
        """.trimIndent()
        val state = UiXmlParser.parse(xml)
        assertEquals(2, state.stageCompleted[0])
        assertEquals(9, state.stageMax[0])
        assertEquals(3, state.stageCompleted[1])
        assertEquals(3, state.stageMax[1])
        assertEquals(0, state.stageCompleted[2])
        assertEquals(9, state.stageMax[2])
        assertTrue(state.mainVisible)
        assertFalse(state.done)
    }

    @Test
    fun loginScreen() {
        val xml = """<hierarchy><node text="登录/注册" resource-id="x" /></hierarchy>"""
        val state = UiXmlParser.parse(xml)
        assertTrue(state.login)
        assertFalse(state.mainVisible)
        assertFalse(state.done)
    }

    private fun load(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: error("missing test resource $name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
