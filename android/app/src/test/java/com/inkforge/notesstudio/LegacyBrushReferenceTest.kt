package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Original web/app.js execution fixture. Double tolerance: abs 1e-9 + relative 1e-10. */
class LegacyBrushReferenceTest {
    private val reference by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/brush-reference.json"))
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
    private fun assertNear(expected: Double, actual: Double, at: String = "") = assertEquals(at, expected, actual,
        1e-9 + kotlin.math.abs(expected) * 1e-10)
    private fun fixtureStroke(fixture: JSONObject) = fixture.getJSONObject("input").copyJson().apply {
        fixture.optJSONObject("settings")?.let { put("settings", it.copyJson()) }
    }
    private fun assertJson(expected: Any?, actual: Any?, at: String) {
        when (expected) {
            is Number -> { assertTrue("$at: number", actual is Number); assertNear(expected.toDouble(), (actual as Number).toDouble(), at) }
            is JSONObject -> { assertTrue("$at: object", actual is JSONObject)
                val found = actual as JSONObject
                assertEquals("$at: keys", expected.keys().asSequence().toSet(), found.keys().asSequence().toSet())
                expected.keys().forEach { key -> assertJson(expected.get(key), found.get(key), "$at.$key") }
            }
            is JSONArray -> { assertTrue("$at: array", actual is JSONArray)
                val found = actual as JSONArray
                assertEquals("$at: size", expected.length(), found.length())
                for (index in 0 until expected.length()) assertJson(expected.get(index), found.get(index), "$at[$index]")
            }
            else -> assertEquals(at, expected, actual)
        }
    }

    @Test fun originalArithmeticAndUtf16Seed() {
        assertEquals("7eb50a657039faf8eef3ea6126701bdb73c10e7581ee6d0d579ca3f56455bfa3",
            reference.getString("sourceSha256"))
        val random = reference.getJSONArray("randomFixtures")
        for (index in 0 until random.length()) {
            val fixture = random.getJSONObject(index)
            val next = LegacyBrush.Random(fixture.getString("seed"))
            val values = fixture.getJSONArray("values")
            for (n in 0 until values.length()) assertEquals("seed $index value $n",
                values.getDouble(n), next.next(), 0.0)
        }
        val widths = reference.getJSONArray("widthFixtures")
        for (index in 0 until widths.length()) {
            val fixture = widths.getJSONObject(index)
            val css = fixture.getDouble("cssWidth"); val width = fixture.getDouble("width")
            assertNear(fixture.getDouble("screenWidth"), LegacyBrush.screenToolWidthToPage(css, width))
            assertNear(fixture.getDouble("toolWidth"), LegacyBrush.toolStrokeWidthToPage(css, width))
        }
        val fixtures = reference.getJSONArray("fixtures")
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index); val stroke = fixtureStroke(fixture)
            assertJson(fixture.getJSONArray("smoothed"), JSONArray(LegacyBrush.smoothed(stroke)), "${fixture.getString("name")}.smooth")
            val rendered = if (stroke.optString("brush") == "pencil") LegacyBrush.pencilRenderPoints(stroke)
                else LegacyBrush.smoothed(stroke)
            assertJson(fixture.getJSONArray("renderPoints"), JSONArray(rendered), "${fixture.getString("name")}.renderPoints")
            val expected = fixture.getJSONArray("widths"); val actual = LegacyBrush.widths(stroke)
            assertEquals(fixture.getString("name"), expected.length(), actual.size)
            actual.forEachIndexed { n, value -> assertNear(expected.getDouble(n), value) }
        }
    }

    @Test fun originalDrawingCommandsAllBrushesAtNormalAndZoomedScale() {
        val fixtures = reference.getJSONArray("fixtures").let { array ->
            (0 until array.length()).associate { i -> array.getJSONObject(i).let { it.getString("name") to it } }
        }
        val renders = reference.getJSONArray("renderFixtures")
        for (index in 0 until renders.length()) {
            val expected = renders.getJSONObject(index)
            val name = expected.getString("name")
            val stroke = fixtureStroke(requireNotNull(fixtures[name])).put("color", "#172033")
            val actual = ArrayList<LegacyBrush.Operation>()
            LegacyBrush.render(stroke, expected.getDouble("scale")) { actual += it }
            val commands = expected.getJSONArray("operations")
            assertEquals("$name operations", commands.length(), actual.size)
            for (command in actual.indices) {
                val wanted = commands.getJSONArray(command)
                val emitted = actual[command]
                assertEquals("$name #$command", wanted.getString(0), emitted.name)
                assertEquals("$name #$command args", wanted.length() - 1, emitted.args.size)
                emitted.args.forEachIndexed { arg, value -> assertJson(wanted.get(arg + 1), value, "$name #$command arg$arg") }
            }
        }
    }

    @Test fun typedLivePointsMatchSerializedCommandsAcrossBrushesAndPointShapes() {
        val brushes = listOf("fountain", "ballpoint", "gel", "brush", "pencil", "fineliner", "highlighter")
        val cases = listOf(Triple("single", 1, 0.0), Triple("pair", 2, .92),
            Triple("long", 191, .37), Triple("long-zero-smoothing", 191, 0.0))
        for ((brushIndex, brush) in brushes.withIndex()) for ((shape, count, smoothing) in cases) {
            val points = (0 until count).map { index ->
                val pressure = when (index % 5) { 0 -> 0f; 1 -> .17f; 2 -> .5f; 3 -> 1f; else -> 1.3f }
                val original = when (index % 7) {
                    2 -> JSONObject().put("x", -1).put("y", -1)
                        .put("azimuth", .7).put("tx", -18).put("ty", 31)
                    3 -> JSONObject().put("x", -1).put("y", -1).put("p", JSONObject.NULL)
                        .put("azimuth", JSONObject.NULL).put("tx", 0).put("ty", -27)
                    4 -> JSONObject().put("x", -1).put("y", -1).put("p", "0")
                        .put("azimuth", "0.35").put("tx", "14").put("ty", JSONObject.NULL)
                    5 -> JSONObject().put("x", -1f).put("y", -1f).put("p", .23f)
                        .put("azimuth", .27f).put("tx", -9.4f).put("ty", 11.6f)
                    else -> null
                }
                InkPoint(index * 2.75f + brushIndex, kotlin.math.sin(index * .31).toFloat() * 19f,
                    pressure, index.toLong(), tilt = (index % 45).toFloat(), orientation = index * .03f,
                    azimuth = if (index % 4 == 0) null else index * .015f,
                    tx = if (index % 3 == 0) null else (index % 61 - 30).toFloat(),
                    ty = if (index % 6 == 0) 0f else (30 - index % 61).toFloat(), original = original)
            }
            val settings = JSONObject()
                .put("pressure", if (count == 2) 0 else .63)
                .put("smoothing", smoothing)
                .put("taper", if (brushIndex % 2 == 0) 0 else .58)
                .put("grain", .81).put("tiltShade", 0).put("opacity", .73)
            val stroke = JSONObject().put("id", "typed-$brush-$shape-\uD83D\uDD8B\uFE0F")
                .put("type", "stroke").put("brush", brush).put("color", "#172033")
                .put("width", if (count == 1) 0 else 5.25).put("settings", settings)
            if (brushIndex % 2 == 1) stroke.put("opacity", .61)
            val serialized = stroke.copyJson().put("points", JSONArray(points.map { it.json() }))
            for (scale in listOf(.75, 3.1)) {
                val expected = ArrayList<LegacyBrush.Operation>()
                val actual = ArrayList<LegacyBrush.Operation>()
                LegacyBrush.render(serialized, scale) { expected += it }
                LegacyBrush.render(stroke, points, scale) { actual += it }
                val label = "$brush/$shape/$scale"
                assertFalse("$label typed render mutated stroke", stroke.has("points"))
                assertEquals("$label operations", expected.size, actual.size)
                expected.indices.forEach { command ->
                    assertEquals("$label #$command name", expected[command].name, actual[command].name)
                    assertEquals("$label #$command args", expected[command].args.size, actual[command].args.size)
                    expected[command].args.indices.forEach { arg ->
                        assertJson(expected[command].args[arg], actual[command].args[arg], "$label #$command arg$arg")
                    }
                }
            }
        }
    }
}
