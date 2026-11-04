package com.sistemadiagnosis.core.pdf

/**
 * Generador mínimo de PDF (1.4) sin dependencias: páginas A4, texto con las
 * fuentes estándar Helvetica, Helvetica-Bold y Courier (codificación WinAnsi,
 * que cubre tildes, ñ, ¿, ¡, ° y ·), rectángulos y líneas.
 *
 * Coordenadas en puntos con el origen arriba a la izquierda (se convierten al
 * sistema del PDF, que lo tiene abajo).
 */
class PdfWriter {
    enum class Font(val resource: String, val baseFont: String) {
        REGULAR("F1", "Helvetica"),
        BOLD("F2", "Helvetica-Bold"),
        MONO("F3", "Courier"),
    }

    /** Color RGB 0-255. */
    data class Color(val r: Int, val g: Int, val b: Int) {
        internal fun operands() = "${n(r / 255.0)} ${n(g / 255.0)} ${n(b / 255.0)}"
    }

    private val pages = mutableListOf<StringBuilder>()
    private var currentIndex = -1
    private val current: StringBuilder get() = pages[currentIndex]

    val pageCount: Int get() = pages.size

    fun newPage() {
        pages += StringBuilder()
        currentIndex = pages.lastIndex
    }

    /** Vuelve a dibujar sobre una página ya creada (p.ej. el pie con el total de páginas). */
    fun goTo(index: Int) {
        currentIndex = index
    }

    fun fillRect(x: Double, y: Double, width: Double, height: Double, color: Color) {
        current.append("${color.operands()} rg ${n(x)} ${n(PAGE_HEIGHT - y - height)} ${n(width)} ${n(height)} re f\n")
    }

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: Color, width: Double = 0.6) {
        current.append("${color.operands()} RG ${n(width)} w ${n(x1)} ${n(PAGE_HEIGHT - y1)} m ${n(x2)} ${n(PAGE_HEIGHT - y2)} l S\n")
    }

    /** Texto con la línea base en [y]. */
    fun text(x: Double, y: Double, text: String, size: Double, font: Font = Font.REGULAR, color: Color = BLACK) {
        current.append("BT /${font.resource} ${n(size)} Tf ${color.operands()} rg ${n(x)} ${n(PAGE_HEIGHT - y)} Td (")
        current.append(escape(text))
        current.append(") Tj ET\n")
    }

    /** Texto alineado a la derecha en [right]. */
    fun textRight(right: Double, y: Double, text: String, size: Double, font: Font = Font.REGULAR, color: Color = BLACK) {
        text(right - width(text, size, font), y, text, size, font, color)
    }

    fun toBytes(): ByteArray {
        val out = ByteSink()
        val offsets = mutableListOf<Int>()
        fun obj(body: String) {
            offsets += out.size
            out.ascii("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        out.ascii("%PDF-1.4\n")
        out.bytes(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        // 1 catálogo, 2 árbol de páginas, 3-5 fuentes; luego cada página y su contenido.
        val firstPage = 6
        val kids = pages.indices.joinToString(" ") { "${firstPage + it * 2} 0 R" }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        Font.entries.forEach { obj("<< /Type /Font /Subtype /Type1 /BaseFont /${it.baseFont} /Encoding /WinAnsiEncoding >>") }
        val fonts = Font.entries.mapIndexed { i, font -> "/${font.resource} ${3 + i} 0 R" }.joinToString(" ")
        pages.forEachIndexed { i, content ->
            val contentId = firstPage + i * 2 + 1
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(PAGE_WIDTH)} ${n(PAGE_HEIGHT)}] /Resources << /Font << $fonts >> >> /Contents $contentId 0 R >>")
            val data = winAnsi(content.toString())
            offsets += out.size
            out.ascii("${offsets.size} 0 obj\n<< /Length ${data.size} >>\nstream\n")
            out.bytes(data)
            out.ascii("\nendstream\nendobj\n")
        }
        val xref = out.size
        out.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.ascii(it.toString().padStart(10, '0') + " 00000 n \n") }
        out.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    companion object {
        const val PAGE_WIDTH = 595.28
        const val PAGE_HEIGHT = 841.89
        val BLACK = Color(0, 0, 0)

        /** Ancho aproximado del texto en puntos (métricas de Helvetica; Courier es fija). */
        fun width(text: String, size: Double, font: Font = Font.REGULAR): Double {
            if (font == Font.MONO) return text.length * 0.6 * size
            val table = if (font == Font.BOLD) BOLD_WIDTHS else REGULAR_WIDTHS
            return text.sumOf { (table[base(it)] ?: DEFAULT_WIDTH).toDouble() } * size / 1000
        }

        /** Parte el texto en líneas que caben en [maxWidth]. */
        fun wrap(text: String, size: Double, maxWidth: Double, font: Font = Font.REGULAR): List<String> {
            val lines = mutableListOf<String>()
            for (paragraph in text.split('\n')) {
                var line = ""
                for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (line.isNotEmpty() && width(candidate, size, font) > maxWidth) {
                        lines += line
                        line = word
                    } else {
                        line = candidate
                    }
                }
                lines += line
            }
            return lines
        }

        private fun n(value: Double): String {
            val rounded = kotlin.math.round(value * 100) / 100
            val text = rounded.toString()
            return if (text.endsWith(".0")) text.dropLast(2) else text
        }

        private fun escape(text: String) = buildString {
            for (c in text) {
                when (c) {
                    '(', ')', '\\' -> append('\\').append(c)
                    '\n', '\r', '\t' -> append(' ')
                    else -> append(c)
                }
            }
        }

        /** Caracteres fuera de Latin-1 que WinAnsi tiene en 0x80-0x9F. */
        private val WIN_ANSI_EXTRA = mapOf(
            '€' to 0x80, '‚' to 0x82, '„' to 0x84, '…' to 0x85, '‘' to 0x91, '’' to 0x92, '“' to 0x93,
            '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97, '™' to 0x99,
        )

        private fun winAnsi(text: String): ByteArray {
            val bytes = ByteArray(text.length)
            text.forEachIndexed { i, c ->
                val code = WIN_ANSI_EXTRA[c] ?: c.code.takeIf { it in 0x20..0x7E || it in 0xA0..0xFF || it == 0x0A } ?: '?'.code
                bytes[i] = code.toByte()
            }
            return bytes
        }

        /** Letra sin tilde para buscar su ancho. */
        private fun base(c: Char): Char = ACCENTS[c] ?: c

        private val ACCENTS = mapOf(
            'á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n',
            'Á' to 'A', 'É' to 'E', 'Í' to 'I', 'Ó' to 'O', 'Ú' to 'U', 'Ü' to 'U', 'Ñ' to 'N',
        )

        private const val DEFAULT_WIDTH = 556

        private val REGULAR_WIDTHS: Map<Char, Int> = widths(
            " 278 ! 278 \" 355 # 556 $ 556 % 889 & 667 ' 191 ( 333 ) 333 * 389 + 584 , 278 - 333 . 278 / 278 " +
                ": 278 ; 278 < 584 = 584 > 584 ? 556 @ 1015 [ 278 ] 278 _ 556 " +
                "A 667 B 667 C 722 D 722 E 667 F 611 G 778 H 722 I 278 J 500 K 667 L 556 M 833 N 722 O 778 P 667 " +
                "Q 778 R 722 S 667 T 611 U 722 V 667 W 944 X 667 Y 667 Z 611 " +
                "a 556 b 556 c 500 d 556 e 556 f 278 g 556 h 556 i 222 j 222 k 500 l 222 m 833 n 556 o 556 p 556 " +
                "q 556 r 333 s 500 t 278 u 556 v 500 w 722 x 500 y 500 z 500 · 278 ° 400 ¿ 611 ¡ 333 • 350 – 556 — 1000 « 556 » 556",
        )

        private val BOLD_WIDTHS: Map<Char, Int> = widths(
            " 278 ! 333 \" 474 # 556 $ 556 % 889 & 722 ' 238 ( 333 ) 333 * 389 + 584 , 278 - 333 . 278 / 278 " +
                ": 333 ; 333 < 584 = 584 > 584 ? 611 @ 975 [ 333 ] 333 _ 556 " +
                "A 722 B 722 C 722 D 722 E 667 F 611 G 778 H 722 I 278 J 556 K 722 L 611 M 833 N 722 O 778 P 667 " +
                "Q 778 R 722 S 667 T 611 U 722 V 667 W 944 X 667 Y 667 Z 611 " +
                "a 556 b 611 c 556 d 611 e 556 f 333 g 611 h 611 i 278 j 278 k 556 l 278 m 889 n 611 o 611 p 611 " +
                "q 611 r 389 s 556 t 333 u 611 v 556 w 778 x 556 y 556 z 500 · 278 ° 400 ¿ 611 ¡ 333 • 350 – 556 — 1000 « 556 » 556",
        )

        private fun widths(spec: String): Map<Char, Int> {
            val parts = spec.split(' ')
            val map = mutableMapOf(' ' to 278)
            var i = 0
            while (i < parts.size - 1) {
                val key = parts[i]
                if (key.length == 1 && parts[i + 1].toIntOrNull() != null) {
                    map[key[0]] = parts[i + 1].toInt()
                    i += 2
                } else {
                    i++
                }
            }
            ('0'..'9').forEach { map[it] = 556 }
            return map
        }
    }

    private class ByteSink {
        private var buffer = ByteArray(16_384)
        var size = 0
            private set

        fun bytes(data: ByteArray) {
            if (size + data.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + data.size))
            data.copyInto(buffer, size)
            size += data.size
        }

        fun ascii(text: String) = bytes(text.encodeToByteArray())

        fun toByteArray() = buffer.copyOf(size)
    }
}
