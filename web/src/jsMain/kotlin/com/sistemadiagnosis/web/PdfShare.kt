package com.sistemadiagnosis.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLAnchorElement

/**
 * Entrega el PDF: con la hoja de compartir del sistema si el navegador puede
 * compartir archivos (WhatsApp, correo, Guardar en Archivos…); si no, se descarga
 * o se abre en una pestaña.
 */
fun sharePdf(bytes: ByteArray, fileName: String) {
    val buffer = bytes.unsafeCast<Int8Array>()
    val options: dynamic = js("({})")
    options.type = "application/pdf"
    val parts = arrayOf<Any>(buffer)
    val navigator = window.navigator.asDynamic()

    val file: dynamic = try {
        js("new File(parts, fileName, options)")
    } catch (e: Throwable) {
        null
    }
    if (file != null && navigator.canShare != null) {
        val data: dynamic = js("({})")
        data.files = arrayOf(file)
        data.title = fileName
        val canShare = try {
            navigator.canShare(data) as Boolean
        } catch (e: Throwable) {
            false
        }
        if (canShare) {
            navigator.share(data).catch { error: dynamic ->
                // Si el usuario cierra la hoja no hay que hacer nada; si falla, se descarga.
                if (error?.name != "AbortError") download(parts, fileName, options)
            }
            return
        }
    }
    download(parts, fileName, options)
}

private fun download(parts: Array<Any>, fileName: String, options: dynamic) {
    val blob: dynamic = js("new Blob(parts, options)")
    val url = js("URL.createObjectURL(blob)").unsafeCast<String>()
    val anchor = document.createElement("a").unsafeCast<HTMLAnchorElement>()
    anchor.href = url
    anchor.download = fileName
    anchor.rel = "noopener"
    document.body?.appendChild(anchor)
    anchor.click()
    anchor.remove()
    toast("Informe PDF descargado", "ok")
    window.setTimeout({ js("URL.revokeObjectURL(url)") }, 60_000)
}
