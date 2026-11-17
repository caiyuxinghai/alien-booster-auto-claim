package com.alienbooster.claimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProtocolTest {
    @Test
    fun roundTripKeepsStdoutNewlinesOutOfTheEnvelope() {
        val stdout = "hello\n世界".toByteArray(Charsets.UTF_8)
        val stderr = "err".toByteArray(Charsets.UTF_8)
        val wire = ShellProtocol.encode(0, stdout, stderr)
        val lines = wire.split('\n')
        assertEquals(3, lines.size)
        assertEquals("0", lines[0])
        assertFalse(lines[1].contains("\n"))
        assertFalse(lines[2].contains("\n"))
        val decoded = ShellProtocol.decode(wire)
        assertEquals(0, decoded.exitCode)
        assertEquals("hello\n世界", decoded.stdout)
        assertEquals("err", decoded.stderr)
        assertTrue(decoded.ok)
    }

    @Test
    fun emptyOutputAndTimeoutCode() {
        val wire = ShellProtocol.encode(-1, ByteArray(0), "timeout".toByteArray(Charsets.UTF_8))
        assertEquals("-1\n\ndGltZW91dA==", wire)
        val decoded = ShellProtocol.decode(wire)
        assertEquals(-1, decoded.exitCode)
        assertEquals("", decoded.stdout)
        assertEquals("timeout", decoded.stderr)
        assertFalse(decoded.ok)
    }

    @Test
    fun knownVectorMatchesNoWrapAlphabet() {
        val wire = ShellProtocol.encode(7, "hi".toByteArray(Charsets.UTF_8), ByteArray(0))
        assertEquals("7\naGk=\n", wire)
        val decoded = ShellProtocol.decode(wire)
        assertEquals(7, decoded.exitCode)
        assertEquals("hi", decoded.stdout)
        assertEquals("", decoded.stderr)
        assertFalse(decoded.ok)
    }

    @Test
    fun malformedPayloadIsAFailure() {
        assertFalse(ShellProtocol.decode("nope").ok)
        assertEquals(-1, ShellProtocol.decode("nope").exitCode)
        assertFalse(ShellProtocol.decode("0\n@@@\n").ok)
        assertFalse(ShellProtocol.decode("").ok)
    }

    @Test
    fun dumpPrefixIsStrippedBeforeParse() {
        val xml = load("main_partial.xml")
        val prefixed = "UI hierchary dumped to: /data/local/tmp/claimer_ui.xml\n$xml"
        val extracted = Shell.extractUiXml(prefixed)
        assertTrue(extracted != null && extracted.startsWith("<?xml"))
        val state = UiXmlParser.parse(prefixed)
        assertEquals(5, state.stageCompleted[0])
        assertEquals("看广告 领时长", state.ctaText)
    }

    private fun load(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: error("missing test resource $name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
