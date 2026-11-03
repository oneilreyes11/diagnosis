package com.sistemadiagnosis.core.vehicle

import com.sistemadiagnosis.core.knowledge.Severity

/** Vehículos soportados. Solo se añaden los que el propietario pide. */
object Vehicles {
    private const val MISFIRE_NOTE =
        "En el 1.5 TFSI lo más habitual son bujías y bobinas. Prueba a intercambiar la bobina del cilindro afectado con la de otro: si el fallo se mueve de cilindro, la bobina está mal; si no, revisa la bujía y el inyector. Con muchos kilómetros, la carbonilla en las válvulas de admisión (típica de la inyección directa) también provoca fallos, sobre todo en frío."

    private const val COOLING_NOTE =
        "Los motores EA211 regulan la temperatura con un módulo de bomba de agua y termostato; las fugas de refrigerante en esa zona son una avería conocida de esta familia. Con el motor frío, revisa el nivel del depósito de expansión y busca restos de refrigerante (rosa o violeta) bajo el motor."

    private const val PARTICULATE_NOTE =
        "Los 1.5 TFSI europeos de esta época llevan filtro de partículas de gasolina (OPF). Los trayectos cortos en frío lo cargan; un trayecto de 20-30 minutos por autovía a régimen constante suele regenerarlo solo."

    private const val FUEL_PRESSURE_NOTE =
        "Este motor usa inyección directa con bomba de alta presión movida por el árbol de levas. Un aviso de presión puede venir de la bomba de baja (en el depósito), de la de alta o del sensor del rail."

    private const val BOOST_NOTE =
        "Antes de pensar en el turbo, revisa manguitos, abrazaderas e intercooler: una fuga en el circuito de presión es la causa más común."

    private const val BATTERY_NOTE =
        "Con Start&Stop, una batería debilitada provoca estos avisos antes que ningún otro síntoma. Si cambias la batería, hay que registrarla en la centralita para que el alternador la cargue bien."

    private val ENGINE = EcuDefinition("01", "Electrónica del motor", 0x7E0, 0x7E8, Severity.HIGH)

    val AUDI_A3_8Y_DFYA = VehicleProfile(
        id = "audi-a3-8y-2021-dfya",
        brand = "Audi",
        model = "A3",
        generation = "8Y",
        platform = "MQB Evo",
        year = 2021,
        engine = EngineSpec(
            code = "DFYA",
            name = "1.5 TFSI",
            family = "EA211 evo",
            power = "150 CV (110 kW)",
            fuel = "Gasolina",
            features = listOf("Inyección directa", "Turbo", "Desconexión de cilindros (ACT)", "Filtro de partículas"),
        ),
        engineEcu = ENGINE,
        // Direcciones de diagnosis UDS habituales del grupo VAG (MQB). Fuera del motor y el
        // cambio, la respuesta llega en petición + 0x6A. Las que no estén montadas no responden.
        modules = listOf(
            ENGINE,
            EcuDefinition("02", "Electrónica del cambio", 0x7E1, 0x7E9, Severity.HIGH),
            EcuDefinition("03", "Electrónica de frenos (ABS/ESC)", 0x713, 0x77D, Severity.HIGH),
            EcuDefinition("08", "Climatizador", 0x746, 0x7B0, Severity.LOW),
            EcuDefinition("09", "Electrónica central", 0x70E, 0x778),
            EcuDefinition("10", "Ayuda de aparcamiento", 0x70A, 0x774, Severity.LOW),
            EcuDefinition("13", "Regulación de distancia (ACC)", 0x757, 0x7C1),
            EcuDefinition("15", "Airbag", 0x715, 0x77F, Severity.HIGH),
            EcuDefinition("16", "Electrónica de la columna de dirección", 0x70C, 0x776),
            EcuDefinition("17", "Cuadro de instrumentos", 0x714, 0x77E, Severity.LOW),
            EcuDefinition("19", "Interfaz de diagnosis (gateway)", 0x710, 0x77A),
            EcuDefinition("42", "Electrónica de la puerta del conductor", 0x74A, 0x7B4, Severity.LOW),
            EcuDefinition("44", "Dirección asistida", 0x712, 0x77C, Severity.HIGH),
            EcuDefinition("52", "Electrónica de la puerta del acompañante", 0x74B, 0x7B5, Severity.LOW),
            EcuDefinition("53", "Freno de estacionamiento", 0x752, 0x7BC, Severity.HIGH),
            EcuDefinition("5F", "Electrónica de información (multimedia)", 0x773, 0x7DD, Severity.LOW),
            EcuDefinition("75", "Telemática (llamada de emergencia)", 0x767, 0x7D1),
        ),
        vinWmis = setOf("WAU", "WUA", "TRU"),
        // Código de modelo del 8Y en el VIN; se confirmará con el VIN real del coche.
        vinModelCodes = setOf("GY", "8Y"),
        dtcNotes = buildMap {
            listOf("P0300", "P0301", "P0302", "P0303", "P0304", "P0351", "P0352", "P0353", "P0354").forEach { put(it, MISFIRE_NOTE) }
            listOf("P0116", "P0117", "P0118", "P0125", "P0128", "P0217", "P2181", "P3081").forEach { put(it, COOLING_NOTE) }
            listOf("P2002", "P2463", "P2452", "P2453").forEach { put(it, PARTICULATE_NOTE) }
            listOf("P0087", "P0088", "P0089", "P0190", "P0191", "P0192", "P0193", "P0627").forEach { put(it, FUEL_PRESSURE_NOTE) }
            listOf("P0299", "P0234", "P2279").forEach { put(it, BOOST_NOTE) }
            listOf("P0562", "P0563", "P0560").forEach { put(it, BATTERY_NOTE) }
        },
    )

    val all = listOf(AUDI_A3_8Y_DFYA)

    fun byId(id: String): VehicleProfile? = all.firstOrNull { it.id == id }
}
