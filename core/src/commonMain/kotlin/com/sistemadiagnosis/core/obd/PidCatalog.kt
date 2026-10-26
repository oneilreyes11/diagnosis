package com.sistemadiagnosis.core.obd

enum class PidCategory(val label: String) {
    ENGINE("Motor"),
    AIR("Aire y turbo"),
    FUEL("Combustible"),
    TEMPERATURE("Temperaturas"),
    EMISSIONS("Emisiones"),
    ELECTRICAL("Eléctrico"),
    TRIP("Uso"),
}

/**
 * Un dato en vivo. [pid] es el PID OBD (servicio 01/02) o, para valores calculados
 * a partir de otros, un identificador ≥ 0x1000.
 */
class PidDefinition(
    val pid: Int,
    val name: String,
    val unit: String,
    val category: PidCategory,
    val description: String,
    val min: Double,
    val max: Double,
    val decimals: Int,
    /** Bytes de datos en la respuesta (0 en valores calculados). */
    val bytes: Int,
    private val formula: (IntArray) -> Double,
) {
    val isDerived: Boolean get() = pid >= DERIVED_BASE

    fun decode(data: IntArray): Double = formula(data)

    companion object {
        const val DERIVED_BASE = 0x1000
    }
}

/** PIDs estándar SAE J1979 con su fórmula, unidad y explicación. */
object PidCatalog {
    const val BOOST = PidDefinition.DERIVED_BASE + 1

    private val definitions = listOf(
        PidDefinition(0x0C, "Régimen del motor", "rpm", PidCategory.ENGINE,
            "Vueltas por minuto del cigüeñal. Al ralentí en caliente suele estar entre 650 y 850 rpm.",
            0.0, 7000.0, 0, 2) { (it[0] * 256 + it[1]) / 4.0 },
        PidDefinition(0x04, "Carga del motor", "%", PidCategory.ENGINE,
            "Porcentaje del par disponible que el motor está entregando en este momento.",
            0.0, 100.0, 0, 1) { it[0] * 100.0 / 255 },
        PidDefinition(0x0D, "Velocidad", "km/h", PidCategory.ENGINE,
            "Velocidad del vehículo según la centralita del motor.",
            0.0, 250.0, 0, 1) { it[0].toDouble() },
        PidDefinition(0x0E, "Avance de encendido", "°", PidCategory.ENGINE,
            "Grados antes del punto muerto superior en que salta la chispa. Si cae mucho bajo carga, la centralita está retrasando el encendido para evitar picado.",
            -10.0, 40.0, 1, 1) { it[0] / 2.0 - 64 },
        PidDefinition(0x49, "Pedal del acelerador", "%", PidCategory.ENGINE,
            "Posición del pedal del acelerador según su sensor.",
            0.0, 100.0, 0, 1) { it[0] * 100.0 / 255 },
        PidDefinition(0x43, "Carga absoluta", "%", PidCategory.ENGINE,
            "Masa de aire por ciclo respecto a la máxima; en motores turbo puede superar el 100 %.",
            0.0, 200.0, 0, 2) { (it[0] * 256 + it[1]) * 100.0 / 255 },

        PidDefinition(BOOST, "Presión de turbo", "bar", PidCategory.AIR,
            "Sobrepresión en el colector respecto a la atmósfera (presión del colector menos la atmosférica). Negativa al ralentí: el motor aspira; positiva cuando el turbo sopla.",
            -1.0, 1.8, 2, 0) { 0.0 },
        PidDefinition(0x0B, "Presión del colector", "kPa", PidCategory.AIR,
            "Presión absoluta en el colector de admisión. Unos 30 kPa al ralentí; por encima de ~100 kPa el turbo está soplando.",
            0.0, 255.0, 0, 1) { it[0].toDouble() },
        PidDefinition(0x10, "Caudal de aire (MAF)", "g/s", PidCategory.AIR,
            "Masa de aire que entra al motor. Al ralentí en caliente, de 2 a 4 g/s.",
            0.0, 150.0, 1, 2) { (it[0] * 256 + it[1]) / 100.0 },
        PidDefinition(0x11, "Mariposa", "%", PidCategory.AIR,
            "Apertura de la mariposa de admisión.",
            0.0, 100.0, 0, 1) { it[0] * 100.0 / 255 },
        PidDefinition(0x4C, "Mariposa comandada", "%", PidCategory.AIR,
            "Apertura que pide la centralita. Debe seguir de cerca a la apertura real.",
            0.0, 100.0, 0, 1) { it[0] * 100.0 / 255 },
        PidDefinition(0x33, "Presión atmosférica", "kPa", PidCategory.AIR,
            "Presión del aire exterior: unos 101 kPa a nivel del mar; baja con la altitud.",
            70.0, 110.0, 0, 1) { it[0].toDouble() },

        PidDefinition(0x23, "Presión de inyección", "bar", PidCategory.FUEL,
            "Presión en el rail de la inyección directa. Baja al ralentí y sube a cientos de bar con carga.",
            0.0, 400.0, 0, 2) { (it[0] * 256 + it[1]) / 10.0 },
        PidDefinition(0x06, "Corrección corta (banco 1)", "%", PidCategory.FUEL,
            "Ajuste instantáneo de la mezcla. Positivo: la centralita añade gasolina (mezcla pobre). Negativo: la quita (mezcla rica). Oscila alrededor de 0.",
            -25.0, 25.0, 1, 1) { (it[0] - 128) * 100.0 / 128 },
        PidDefinition(0x07, "Corrección larga (banco 1)", "%", PidCategory.FUEL,
            "Ajuste aprendido de la mezcla. Entre −5 y +5 % es normal; más de ±10 % apunta a tomas de aire, inyectores o caudalímetro.",
            -25.0, 25.0, 1, 1) { (it[0] - 128) * 100.0 / 128 },
        PidDefinition(0x5E, "Consumo instantáneo", "L/h", PidCategory.FUEL,
            "Litros de gasolina por hora que está consumiendo el motor ahora mismo.",
            0.0, 40.0, 1, 2) { (it[0] * 256 + it[1]) / 20.0 },
        PidDefinition(0x2F, "Nivel de combustible", "%", PidCategory.FUEL,
            "Nivel del depósito según el aforador.",
            0.0, 100.0, 0, 1) { it[0] * 100.0 / 255 },

        PidDefinition(0x05, "Refrigerante", "°C", PidCategory.TEMPERATURE,
            "Temperatura del líquido refrigerante. En marcha normal, entre 85 y 105 °C según la carga.",
            -40.0, 130.0, 0, 1) { it[0] - 40.0 },
        PidDefinition(0x5C, "Aceite", "°C", PidCategory.TEMPERATURE,
            "Temperatura del aceite del motor. Evita exigir al motor hasta que supere los 80 °C.",
            -40.0, 150.0, 0, 1) { it[0] - 40.0 },
        PidDefinition(0x0F, "Aire de admisión", "°C", PidCategory.TEMPERATURE,
            "Temperatura del aire tras el intercooler. Si sube mucho, el motor pierde potencia.",
            -40.0, 90.0, 0, 1) { it[0] - 40.0 },
        PidDefinition(0x46, "Temperatura exterior", "°C", PidCategory.TEMPERATURE,
            "Temperatura del aire exterior.",
            -40.0, 60.0, 0, 1) { it[0] - 40.0 },
        PidDefinition(0x3C, "Catalizador", "°C", PidCategory.TEMPERATURE,
            "Temperatura estimada del catalizador (banco 1, sensor 1). Funciona bien por encima de unos 300 °C.",
            0.0, 1000.0, 0, 2) { (it[0] * 256 + it[1]) / 10.0 - 40 },

        PidDefinition(0x34, "Lambda medida", "λ", PidCategory.EMISSIONS,
            "Relación aire/gasolina medida por la sonda antes del catalizador. 1,000 es la mezcla ideal; menos de 1 es rica y más de 1 pobre.",
            0.7, 1.3, 3, 4) { (it[0] * 256 + it[1]) * 2.0 / 65536 },
        PidDefinition(0x44, "Lambda objetivo", "λ", PidCategory.EMISSIONS,
            "Mezcla que pide la centralita. A plena carga la enriquece (por debajo de 1) para proteger el motor.",
            0.7, 1.3, 3, 2) { (it[0] * 256 + it[1]) * 2.0 / 65536 },
        PidDefinition(0x15, "Sonda tras catalizador", "V", PidCategory.EMISSIONS,
            "Tensión de la sonda posterior. Con el catalizador sano es estable, alrededor de 0,6-0,8 V.",
            0.0, 1.275, 2, 2) { it[0] / 200.0 },

        PidDefinition(0x42, "Tensión de la centralita", "V", PidCategory.ELECTRICAL,
            "Alimentación de la centralita del motor: unos 12,4 V con el motor parado y 13,5-14,8 V en marcha (carga del alternador).",
            10.0, 16.0, 2, 2) { (it[0] * 256 + it[1]) / 1000.0 },

        PidDefinition(0x1F, "Tiempo desde el arranque", "s", PidCategory.TRIP,
            "Segundos desde que se arrancó el motor.",
            0.0, 65535.0, 0, 2) { (it[0] * 256 + it[1]).toDouble() },
        PidDefinition(0x31, "Km desde el borrado", "km", PidCategory.TRIP,
            "Distancia recorrida desde que se borraron las averías por última vez.",
            0.0, 65535.0, 0, 2) { (it[0] * 256 + it[1]).toDouble() },
        PidDefinition(0x30, "Calentamientos desde el borrado", "", PidCategory.TRIP,
            "Veces que el motor ha llegado a temperatura desde el último borrado.",
            0.0, 255.0, 0, 1) { it[0].toDouble() },
        PidDefinition(0x21, "Km con el testigo encendido", "km", PidCategory.TRIP,
            "Distancia recorrida con el testigo de motor encendido.",
            0.0, 65535.0, 0, 2) { (it[0] * 256 + it[1]).toDouble() },
        PidDefinition(0x4E, "Minutos desde el borrado", "min", PidCategory.TRIP,
            "Tiempo de motor en marcha desde el último borrado de averías.",
            0.0, 65535.0, 0, 2) { (it[0] * 256 + it[1]).toDouble() },
        PidDefinition(0x4D, "Minutos con el testigo encendido", "min", PidCategory.TRIP,
            "Tiempo de motor en marcha con el testigo encendido.",
            0.0, 65535.0, 0, 2) { (it[0] * 256 + it[1]).toDouble() },
    )

    private val byPid = definitions.associateBy { it.pid }

    val all: List<PidDefinition> get() = definitions

    /** Los datos que se muestran primero en "En vivo". */
    val featured = listOf(0x0C, BOOST, 0x05, 0x5C, 0x04, 0x10, 0x0F, 0x23, 0x0E, 0x07, 0x42, 0x34)

    /** Datos necesarios para calcular cada valor derivado. */
    val derivedInputs: Map<Int, List<Int>> = mapOf(BOOST to listOf(0x0B, 0x33))

    operator fun get(pid: Int): PidDefinition? = byPid[pid]

    /** Bytes de datos del PID; incluye los PIDs de mapa de soportados (00, 20, 40…) y el 01. */
    fun byteCount(pid: Int): Int? = when {
        pid % 0x20 == 0 -> 4
        pid == 0x01 -> 4
        else -> byPid[pid]?.bytes?.takeIf { it > 0 }
    }

    /** Calcula los valores derivados a partir de los leídos. */
    fun derive(values: Map<Int, Double>): Map<Int, Double> = buildMap {
        val map = values[0x0B]
        val baro = values[0x33]
        if (map != null && baro != null) put(BOOST, (map - baro) / 100.0)
    }
}
