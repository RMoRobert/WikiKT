package com.wikikt.auth

import qrcode.raw.QRCodeProcessor

/**
 * Renders [data] as an inline QR-code SVG. Uses qrcode-kotlin only to compute the module matrix (its own
 * renderers are raster/AWT); the SVG here is one `<path>` of unit squares plus the spec's 4-module quiet
 * zone, so it's crisp at any size, embeds directly in the page (no external request or `data:` URI, so it's
 * CSP-friendly), and scales via `width`/`height`. Manual key entry remains the fallback for anyone who
 * can't scan.
 */
object QrSvg {
    private const val QUIET_ZONE = 4 // modules of white margin required around a QR code
    private const val PIXELS = 200 // rendered size; the viewBox is in module units so this just scales

    fun render(data: String): String {
        val matrix = QRCodeProcessor(data).encode()
        val dim = matrix.size + QUIET_ZONE * 2
        val path = StringBuilder()
        for (row in matrix) {
            for (square in row) {
                if (square.dark) {
                    // A unit black square at the module's position, offset by the quiet zone.
                    path.append("M").append(square.col + QUIET_ZONE).append(' ').append(square.row + QUIET_ZONE).append("h1v1h-1z")
                }
            }
        }
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(dim).append(' ').append(dim)
            append("\" width=\"").append(PIXELS).append("\" height=\"").append(PIXELS)
            append("\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"QR code for authenticator setup\">")
            append("<rect width=\"").append(dim).append("\" height=\"").append(dim).append("\" fill=\"#ffffff\"/>")
            append("<path fill=\"#000000\" d=\"").append(path).append("\"/>")
            append("</svg>")
        }
    }
}
