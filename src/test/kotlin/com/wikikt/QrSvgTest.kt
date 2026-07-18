package com.wikikt

import com.wikikt.auth.QrSvg
import kotlin.test.Test
import kotlin.test.assertTrue

class QrSvgTest {
    @Test
    fun `renders a well-formed svg with module squares`() {
        val svg = QrSvg.render("otpauth://totp/WikiKT:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=WikiKT&digits=6&period=30")
        assertTrue(svg.startsWith("<svg"), "is an svg element")
        assertTrue(svg.contains("viewBox=\"0 0 "), "has a viewBox in module units")
        assertTrue(svg.contains("<path fill=\"#000000\""), "has a module path")
        assertTrue(svg.contains("h1v1h-1z"), "draws unit-square modules")
        assertTrue(svg.trimEnd().endsWith("</svg>"), "is a closed element")
    }

    @Test
    fun `different data produces different codes`() {
        assertTrue(QrSvg.render("one") != QrSvg.render("two"), "the matrix reflects the data")
    }
}
