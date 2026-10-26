package com.sistemadiagnosis.core.elm

import com.sistemadiagnosis.core.DiagnosisException

open class ElmException(message: String) : DiagnosisException(message)

class ElmTimeoutException(val command: String, val partial: String) :
    ElmException("Sin respuesta completa a '$command'" + if (partial.isBlank()) "" else " (recibido: '${partial.trim()}')")

class ElmCommandException(val command: String, val response: ElmResponse.Error) :
    ElmException("'$command': ${response.error.description} (${response.lines.joinToString(" / ")})")
