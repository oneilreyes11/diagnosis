package com.sistemadiagnosis.core

/** Base de los errores esperables del sistema (conexión, adaptador, vehículo). */
open class DiagnosisException(message: String, cause: Throwable? = null) : Exception(message, cause)
