package com.sistemadiagnosis.core.vehicle

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
        engineEcu = EcuDefinition("01", "Electrónica del motor", requestId = 0x7E0, responseId = 0x7E8),
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
