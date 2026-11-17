package com.alienbooster.claimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockerScanTest {
    @Test
    fun jumpDialogTapsDeny() {
        val xml = """
            <hierarchy>
              <node text="想要打开其他应用" bounds="[0,0][100,40]" />
              <node text="拒绝" bounds="[10,20][110,80]" />
              <node text="允许" bounds="[200,20][300,80]" />
            </hierarchy>
        """.trimIndent()
        val hit = BlockerScan.find(xml)
        assertEquals("jump", hit?.kind)
        assertEquals(60, hit?.x)
        assertEquals(50, hit?.y)
    }

    @Test
    fun exitConfirmTapsCancel() {
        val xml = """
            <hierarchy>
              <node text="确定要退出吗" bounds="[0,0][40,40]" />
              <node text="取消" bounds="[10,10][50,50]" />
              <node text="确认" bounds="[80,10][120,50]" />
            </hierarchy>
        """.trimIndent()
        val hit = BlockerScan.find(xml)
        assertEquals("exit", hit?.kind)
        assertEquals(30, hit?.x)
        assertEquals(30, hit?.y)
    }

    @Test
    fun adLoadFailureTapsRetryAndIsNotDone() {
        val xml = """
            <hierarchy>
              <node text="广告加载失败" bounds="[0,0][20,20]" />
              <node text="点击重试" bounds="[0,0][40,20]" />
            </hierarchy>
        """.trimIndent()
        val hit = BlockerScan.find(xml)
        assertEquals("retry", hit?.kind)
        assertEquals(20, hit?.x)
        assertEquals(10, hit?.y)
        val state = UiXmlParser.parse(xml)
        org.junit.Assert.assertFalse(state.done)
        org.junit.Assert.assertFalse(RewardPolicy.credited(state, state))
    }

    @Test
    fun normalCtaIsNotABlocker() {
        val xml = """
            <hierarchy>
              <node text="看广告 领时长" bounds="[482,2229][789,2295]" />
            </hierarchy>
        """.trimIndent()
        assertNull(BlockerScan.find(xml))
        assertNull(BlockerScan.find(null))
        assertNull(BlockerScan.find("not xml"))
    }
}
