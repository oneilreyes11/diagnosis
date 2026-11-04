package com.sistemadiagnosis.web

/** Iconos de trazo de 24 px, dibujados para esta app. */
object Icons {
    private val paths = mapOf(
        "home" to """<path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"/>""",
        "alert" to """<path d="M12 3.5 22 20H2z"/><path d="M12 10v4.5"/><path d="M12 17.5h.01"/>""",
        "activity" to """<path d="M3 12h4l2.5-7 5 14L17 12h4"/>""",
        "shield" to """<path d="M12 3 4.5 6v5.5c0 4.7 3.2 8.2 7.5 9.5 4.3-1.3 7.5-4.8 7.5-9.5V6z"/><path d="m8.8 12 2.2 2.2 4.4-4.4"/>""",
        "more" to """<path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>""",
        "bluetooth" to """<path d="m7 7 10 10-5 5V2l5 5L7 17"/>""",
        "engine" to """<path d="M3 11h2V9h3V7h7v2h3l2 3h1v5h-1l-2 2H9l-2-2H5v-2H3z"/><path d="M10 7V5h3v2"/>""",
        "check" to """<path d="m5 12.5 4.5 4.5L19 7.5"/>""",
        "check-circle" to """<circle cx="12" cy="12" r="9"/><path d="m8 12.3 2.8 2.8L16 9.8"/>""",
        "chevron" to """<path d="m9 5 7 7-7 7"/>""",
        "back" to """<path d="m15 5-7 7 7 7"/>""",
        "close" to """<path d="M6 6l12 12M18 6 6 18"/>""",
        "trash" to """<path d="M4 7h16"/><path d="M9 7V4h6v3"/><path d="m6 7 1 13h10l1-13"/><path d="M10 11v6M14 11v6"/>""",
        "share" to """<path d="M12 15V3"/><path d="m7.5 7.5 4.5-4.5 4.5 4.5"/><path d="M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7"/>""",
        "refresh" to """<path d="M20 11a8 8 0 0 0-14.9-3.5M4 4v4h4"/><path d="M4 13a8 8 0 0 0 14.9 3.5M20 20v-4h-4"/>""",
        "play" to """<path d="M7 5v14l11-7z"/>""",
        "pause" to """<path d="M8 5v14M16 5v14"/>""",
        "battery" to """<rect x="3" y="7" width="16" height="10" rx="2"/><path d="M21 11v2"/><path d="M7 10v4M11 10v4"/>""",
        "gauge" to """<path d="M4.5 17a8.5 8.5 0 1 1 15 0"/><path d="m12 13 3.5-4"/>""",
        "info" to """<circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/>""",
        "car" to """<path d="M5 16H3v-4l2-5a2 2 0 0 1 1.9-1.4h10.2A2 2 0 0 1 19 7l2 5v4h-2"/><path d="M3 12h18"/><circle cx="7.5" cy="16.5" r="2"/><circle cx="16.5" cy="16.5" r="2"/><path d="M9.5 16.5h5"/>""",
        "search" to """<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>""",
        "clock" to """<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>""",
        "external" to """<path d="M14 4h6v6"/><path d="M20 4 11 13"/><path d="M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5"/>""",
        "logout" to """<path d="M15 4h4v16h-4"/><path d="m10 8-4 4 4 4"/><path d="M6 12h10"/>""",
        "terminal" to """<path d="m5 8 4 4-4 4"/><path d="M12 17h7"/>""",
        "snapshot" to """<rect x="3" y="6" width="18" height="14" rx="2"/><path d="M8 6l1.5-2h5L16 6"/><circle cx="12" cy="13" r="3.5"/>""",
        "copy" to """<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>""",
    )

    fun svg(name: String, cls: String = "icon"): String =
        """<svg class="$cls" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" """ +
            """stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name].orEmpty()}</svg>"""
}
