package com.sistemadiagnosis.core.knowledge

import com.sistemadiagnosis.core.knowledge.Severity.HIGH
import com.sistemadiagnosis.core.knowledge.Severity.LOW
import com.sistemadiagnosis.core.knowledge.Severity.MEDIUM

/**
 * Averías documentadas, en español. Títulos según SAE J2012; explicaciones,
 * causas (de más a menos probable) y consejos redactados para el conductor.
 */
internal object DtcCatalogEs {
    private val entries = mutableMapOf<String, DtcEntry>()

    operator fun get(code: String): DtcEntry? = entries[code]

    val size: Int get() = entries.size

    private fun add(
        code: String,
        title: String,
        severity: Severity,
        explanation: String,
        symptoms: List<String>,
        causes: List<String>,
        advice: String,
    ) {
        entries[code] = DtcEntry(title, severity, explanation, symptoms, causes, advice)
    }

    init {
        misfires()
        mixture()
        catalystAndParticulateFilter()
        oxygenSensors()
        airMetering()
        cooling()
        camAndCrank()
        fuelPressure()
        boost()
        throttleAndPedal()
        knock()
        evap()
        idleSpeedAndBrake()
        electricalAndEcu()
        oilPressure()
        network()
    }

    private fun misfires() {
        add(
            "P0300", "Fallos de encendido en varios cilindros", HIGH,
            "La centralita detecta que la combustión falla en varios cilindros o sin un patrón fijo. Lo nota por pequeñas " +
                "variaciones en la velocidad del cigüeñal. Si los fallos son continuos, la gasolina sin quemar llega al " +
                "catalizador y puede recalentarlo y romperlo.",
            listOf("Tirones o temblores, sobre todo al acelerar", "Ralentí irregular", "Pérdida de potencia", "Testigo de motor parpadeando si los fallos son graves"),
            listOf(
                "Bujías gastadas o con la separación incorrecta",
                "Bobinas de encendido defectuosas",
                "Toma de aire falsa (manguitos, junta del colector, respiradero del cárter)",
                "Presión de combustible insuficiente",
                "Gasolina de mala calidad o con agua",
            ),
            "Si el testigo parpadea, levanta el pie y evita acelerar a fondo: estás dañando el catalizador. Revisa primero bujías " +
                "y bobinas; si fallan todos los cilindros a la vez, busca una causa común (aire o combustible).",
        )
        for (cylinder in 1..4) {
            add(
                "P030$cylinder", "Fallo de encendido en el cilindro $cylinder", HIGH,
                "La centralita ha detectado que el cilindro $cylinder no quema bien la mezcla. Mientras el fallo dure, ese " +
                    "cilindro no empuja y la gasolina sin quemar acaba en el catalizador.",
                listOf("Motor que tiembla, sobre todo al ralentí", "Tirones al acelerar", "Pérdida de potencia", "Testigo de motor fijo o parpadeando"),
                listOf(
                    "Bujía del cilindro $cylinder gastada o dañada",
                    "Bobina del cilindro $cylinder defectuosa",
                    "Inyector del cilindro $cylinder sucio o averiado",
                    "Carbonilla en las válvulas de admisión",
                    "Compresión baja en el cilindro $cylinder (válvulas, segmentos)",
                ),
                "Intercambia la bobina del cilindro $cylinder con la de otro cilindro y borra las averías: si el fallo se mueve, " +
                    "cambia la bobina. Si sigue en el cilindro $cylinder, revisa bujía, inyector y compresión. Si el testigo " +
                    "parpadea, no fuerces el motor.",
            )
            add(
                "P035$cylinder", "Circuito de la bobina de encendido del cilindro $cylinder", HIGH,
                "La centralita activa la bobina del cilindro $cylinder pero no detecta la corriente esperada: hay un fallo " +
                    "eléctrico en la bobina o en su circuito. Ese cilindro dejará de encender.",
                listOf("Fallo de encendido en el cilindro $cylinder", "Motor que tiembla", "Pérdida de potencia"),
                listOf(
                    "Bobina del cilindro $cylinder averiada",
                    "Conector de la bobina suelto u oxidado",
                    "Cable cortado o en cortocircuito",
                    "Etapa de potencia de la centralita (poco frecuente)",
                ),
                "Revisa el conector de la bobina y prueba a intercambiarla con la de otro cilindro para confirmar el diagnóstico.",
            )
            add(
                "P020$cylinder", "Circuito del inyector del cilindro $cylinder", HIGH,
                "La centralita detecta una anomalía eléctrica en el inyector del cilindro $cylinder (circuito abierto o " +
                    "resistencia fuera de rango). Sin inyección, ese cilindro no trabaja.",
                listOf("Fallo de encendido en el cilindro $cylinder", "Motor que tiembla", "Pérdida de potencia"),
                listOf(
                    "Inyector del cilindro $cylinder averiado",
                    "Conector o cableado del inyector dañado",
                    "Etapa de potencia de la centralita (poco frecuente)",
                ),
                "Comprueba el conector del inyector. Los inyectores de inyección directa trabajan a muy alta presión: su " +
                    "sustitución es trabajo de taller.",
            )
        }
    }

    private fun mixture() {
        val leanCauses = listOf(
            "Toma de aire falsa tras el caudalímetro (manguitos, junta del colector, válvula del respiradero del cárter)",
            "Caudalímetro o sensor de presión del colector sucio o descalibrado",
            "Presión de combustible baja (bomba o filtro)",
            "Inyectores obstruidos",
            "Fuga de escape antes de la sonda lambda",
        )
        val richCauses = listOf(
            "Inyector que gotea o no cierra bien",
            "Presión de combustible demasiado alta",
            "Válvula de purga del cánister EVAP bloqueada abierta",
            "Caudalímetro que mide más aire del real",
            "Sensor de temperatura del refrigerante que marca frío",
            "Aceite contaminado con gasolina (muchos trayectos cortos)",
        )
        add(
            "P0171", "Mezcla demasiado pobre (banco 1)", MEDIUM,
            "La centralita está añadiendo más gasolina de lo normal y ha llegado al límite de corrección: entra más aire " +
                "del que mide o llega menos gasolina de la que pide.",
            listOf("Ralentí inestable", "Tirones o falta de fuerza", "A veces ningún síntoma"),
            leanCauses,
            "Mira la «Corrección larga» en En vivo: si es muy positiva al ralentí y se normaliza al acelerar, casi seguro " +
                "es una toma de aire.",
        )
        add(
            "P0172", "Mezcla demasiado rica (banco 1)", MEDIUM,
            "La centralita está quitando gasolina al máximo y aun así la mezcla sigue rica.",
            listOf("Consumo más alto", "Olor a gasolina en el escape", "Humo negro en casos graves"),
            richCauses,
            "Una mezcla rica mantenida contamina las bujías y daña el catalizador: conviene revisarlo pronto.",
        )
        for ((code, lean, condition) in listOf(
            Triple("P2177", true, "fuera del ralentí"), Triple("P2178", false, "fuera del ralentí"),
            Triple("P2187", true, "al ralentí"), Triple("P2188", false, "al ralentí"),
        )) {
            add(
                code, "Mezcla demasiado ${if (lean) "pobre" else "rica"} $condition (banco 1)", MEDIUM,
                "La corrección de mezcla ha llegado a su límite $condition: la centralita no consigue mantener la mezcla " +
                    "ideal ${if (lean) "añadiendo" else "quitando"} gasolina.",
                listOf("Ralentí o respuesta irregulares", "Consumo alterado"),
                if (lean) leanCauses else richCauses,
                if (condition == "al ralentí") "Al ralentí el aire que entra es poco, así que cualquier toma de aire pesa mucho: es la primera sospecha."
                else "Fuera del ralentí, la presión de combustible y el caudalímetro son los primeros sospechosos.",
            )
        }
    }

    private fun catalystAndParticulateFilter() {
        add(
            "P0420", "Eficiencia del catalizador por debajo del umbral (banco 1)", MEDIUM,
            "La sonda lambda posterior oscila casi como la anterior: el catalizador ya no almacena oxígeno ni limpia los " +
                "gases como debería.",
            listOf("Normalmente ninguno", "Puede no superar la prueba de gases de la ITV"),
            listOf(
                "Catalizador envejecido o dañado por fallos de encendido anteriores",
                "Sonda lambda posterior defectuosa o lenta",
                "Fuga de escape cerca de las sondas",
                "Mezcla mal regulada (correcciones de combustible altas)",
            ),
            "Antes de cambiar el catalizador, descarta fugas de escape, una sonda posterior lenta y cualquier fallo de " +
                "encendido: si los había, cambiar solo el catalizador no lo soluciona.",
        )
        add(
            "P2002", "Eficiencia del filtro de partículas por debajo del umbral (banco 1)", MEDIUM,
            "La centralita compara la presión antes y después del filtro y concluye que no retiene las partículas como " +
                "debería: puede estar dañado o el sensor de presión diferencial da lecturas erróneas.",
            listOf("Normalmente ninguno", "Testigo de motor"),
            listOf(
                "Tubos del sensor de presión diferencial rotos, sueltos o invertidos",
                "Sensor de presión diferencial defectuoso",
                "Filtro de partículas dañado o perforado",
                "Fuga en el escape",
            ),
            "Revisa primero los tubos del sensor de presión diferencial: si están rotos o mal conectados dan justo este código.",
        )
        add(
            "P2463", "Filtro de partículas: acumulación de hollín", MEDIUM,
            "El filtro tiene más hollín del permitido y no consigue regenerarse (quemarlo) por sí solo.",
            listOf("Pérdida de potencia (modo de emergencia)", "Testigo de motor"),
            listOf(
                "Muchos trayectos cortos con el motor frío",
                "Regeneraciones interrumpidas",
                "Consumo de aceite elevado",
                "Sensor de presión diferencial defectuoso",
            ),
            "Haz un trayecto de 20-30 minutos por autovía a régimen constante. Si la carga es muy alta, el taller tendrá " +
                "que hacer una regeneración forzada.",
        )
        add(
            "P2452", "Sensor de presión del filtro de partículas: circuito", MEDIUM,
            "La señal del sensor de presión diferencial del filtro de partículas no es válida. Sin ella, la centralita no " +
                "sabe cuánto hollín acumula el filtro.",
            listOf("Testigo de motor", "Puede limitar la potencia"),
            listOf("Conector o cableado del sensor", "Sensor averiado"),
            "Revisa el conector del sensor y su cableado, que pasa cerca del escape.",
        )
        add(
            "P2453", "Sensor de presión del filtro de partículas: rango o funcionamiento", MEDIUM,
            "El sensor de presión diferencial del filtro da valores que no cuadran con las condiciones de funcionamiento.",
            listOf("Testigo de motor", "Regeneraciones demasiado frecuentes o inexistentes"),
            listOf("Tubos del sensor obstruidos, rotos o sueltos", "Sensor averiado", "Filtro muy cargado"),
            "Comprueba que los tubos del sensor no tengan grietas ni condensación acumulada.",
        )
    }

    private fun oxygenSensors() {
        for (sensor in 1..2) {
            // P0130-P0135 (sensor 1) y P0136-P0141 (sensor 2): la numeración SAE avanza en decimal.
            val base = if (sensor == 1) 130 else 136
            val where = if (sensor == 1) "antes del catalizador" else "después del catalizador"
            val role = if (sensor == 1) "regula la mezcla" else "vigila el catalizador"
            val severity = if (sensor == 1) MEDIUM else LOW
            val name = "Sonda lambda $where (banco 1, sensor $sensor)"
            val commonCauses = listOf(
                "Sonda envejecida o contaminada (aceite, refrigerante, silicona)",
                "Conector o cableado dañado (pasa cerca del escape)",
                "Fuga de escape cerca de la sonda",
            )
            val effect = if (sensor == 1) "Sin una señal fiable, la centralita regula la mezcla a ciegas: más consumo y emisiones."
            else "Afecta a la vigilancia del catalizador, no al funcionamiento del motor."
            val symptoms = if (sensor == 1) listOf("Consumo más alto", "Ralentí irregular", "Testigo de motor") else listOf("Testigo de motor")
            val variants = listOf(
                "circuito" to "La centralita detecta un fallo eléctrico en el circuito de la sonda.",
                "tensión baja" to "La señal de la sonda se queda por debajo de lo posible en funcionamiento normal.",
                "tensión alta" to "La señal de la sonda se queda por encima de lo posible en funcionamiento normal.",
                "respuesta lenta" to "La sonda tarda demasiado en reaccionar a los cambios de mezcla.",
                "sin actividad" to "La señal de la sonda no cambia aunque la mezcla sí lo hace.",
            )
            variants.forEachIndexed { index, (variant, detail) ->
                add(
                    "P0${base + index}", "$name: $variant", severity,
                    "$detail Esta sonda está $where y $role. $effect",
                    symptoms,
                    commonCauses,
                    "Comprueba el conector y el cable de la sonda. Si están bien, la sonda suele ser la culpable; usa una de la referencia correcta.",
                )
            }
            val heater = "Calefacción de la sonda lambda $where (banco 1, sensor $sensor): circuito"
            val heaterExplanation = "La resistencia que calienta la sonda no funciona: la sonda tarda mucho en dar señal tras arrancar."
            val heaterCauses = listOf("Resistencia de la sonda cortada (sonda averiada)", "Fusible de la calefacción fundido", "Conector o cableado dañado")
            val heaterAdvice = "Mide la resistencia de calefacción en el conector de la sonda; si está abierta, cambia la sonda."
            add("P0${base + 5}", heater, LOW,heaterExplanation, listOf("Más emisiones tras arrancar en frío"), heaterCauses, heaterAdvice)
            add(if (sensor == 1) "P0030" else "P0036", "Control de la calefacción de la sonda lambda $where (banco 1, sensor $sensor)", LOW,
                heaterExplanation, listOf("Más emisiones tras arrancar en frío"), heaterCauses, heaterAdvice)
        }
        for ((code, lean) in listOf("P2195" to true, "P2196" to false)) {
            add(
                code, "Sonda lambda antes del catalizador: señal fija en ${if (lean) "pobre" else "rico"} (banco 1, sensor 1)", MEDIUM,
                "La sonda indica mezcla ${if (lean) "pobre" else "rica"} todo el tiempo aunque la centralita corrige: o la sonda " +
                    "no mide bien o la mezcla está realmente descontrolada.",
                listOf("Consumo alterado", "Ralentí irregular"),
                if (lean) listOf("Toma de aire o fuga de escape antes de la sonda", "Sonda envejecida", "Presión de combustible baja")
                else listOf("Inyector que gotea", "Sonda envejecida o contaminada", "Purga del cánister abierta"),
                "Compara «Lambda medida» y «Lambda objetivo» en En vivo: si la objetivo es 1,000 y la medida no la sigue, sospecha de la sonda.",
            )
        }
        for ((code, lean) in listOf("P2096" to true, "P2097" to false)) {
            add(
                code, "Corrección tras el catalizador: mezcla demasiado ${if (lean) "pobre" else "rica"} (banco 1)", MEDIUM,
                "La sonda posterior obliga a corregir la mezcla más de lo permitido: la sonda anterior y la posterior no se ponen de acuerdo.",
                listOf("Normalmente ninguno", "Testigo de motor"),
                listOf("Fuga de escape entre las sondas", "Sonda anterior desajustada (envejecida)", "Sonda posterior defectuosa", "Catalizador deteriorado"),
                "Busca fugas de escape primero: una pequeña fuga cerca de la sonda posterior da exactamente este código.",
            )
        }
    }

    private fun airMetering() {
        add(
            "P0101", "Caudalímetro (MAF): rango o funcionamiento", MEDIUM,
            "La medida del caudalímetro no cuadra con lo que la centralita calcula a partir del régimen, la mariposa y la presión del colector.",
            listOf("Tirones", "Pérdida de potencia", "Ralentí irregular"),
            listOf(
                "Caudalímetro sucio (aceite del filtro, polvo)",
                "Toma de aire entre el caudalímetro y el motor",
                "Filtro de aire obstruido o mal colocado",
                "Fuga en el circuito del turbo",
            ),
            "No limpies el sensor con productos que no sean específicos para caudalímetros. Revisa filtro y manguitos antes de cambiarlo.",
        )
        for ((code, level) in listOf("P0102" to "baja", "P0103" to "alta")) {
            add(
                code, "Caudalímetro (MAF): señal $level", MEDIUM,
                "La señal del caudalímetro está fuera de su rango eléctrico. La centralita pasa a estimar el aire con otros sensores.",
                listOf("Tirones", "Pérdida de potencia"),
                listOf("Conector desconectado u oxidado", "Cableado dañado", "Caudalímetro averiado"),
                "Revisa el conector del caudalímetro: es la causa más rápida de descartar.",
            )
        }
        add(
            "P0106", "Sensor de presión del colector (MAP): rango o funcionamiento", MEDIUM,
            "La presión que mide el sensor del colector no es coherente con el resto de datos del motor.",
            listOf("Tirones", "Pérdida de potencia"),
            listOf("Sensor sucio (vapores de aceite del respiradero)", "Fuga en el colector o en el circuito del turbo", "Conector dañado", "Sensor averiado"),
            "Con el motor parado y el contacto puesto, la presión del colector debe ser casi igual a la atmosférica.",
        )
        for ((code, level) in listOf("P0107" to "baja", "P0108" to "alta")) {
            add(
                code, "Sensor de presión del colector (MAP): señal $level", MEDIUM,
                "La señal del sensor de presión del colector está fuera de su rango eléctrico.",
                listOf("Tirones", "Pérdida de potencia"),
                listOf("Conector o cableado dañado", "Sensor averiado", "Alimentación de 5 V de los sensores"),
                "Revisa el conector del sensor y comprueba si hay otras averías de sensores a la vez (alimentación común).",
            )
        }
        add(
            "P0112", "Sensor de temperatura del aire de admisión: señal baja", LOW,
            "El sensor marca una temperatura del aire imposiblemente alta, normalmente por un cortocircuito.",
            listOf("Ninguno o leve pérdida de potencia"),
            listOf("Cable del sensor en cortocircuito a masa", "Sensor averiado"),
            "Suele ser un problema de cableado o del propio sensor, a veces integrado en el caudalímetro o en el sensor de presión.",
        )
        add(
            "P0113", "Sensor de temperatura del aire de admisión: señal alta", LOW,
            "El sensor marca una temperatura del aire imposiblemente baja, normalmente porque el circuito está abierto.",
            listOf("Ninguno o leve pérdida de potencia"),
            listOf("Conector desconectado", "Cable cortado", "Sensor averiado"),
            "Comprueba que el conector esté bien encajado, sobre todo si se ha trabajado hace poco en la admisión.",
        )
    }

    private fun cooling() {
        add(
            "P0116", "Sensor de temperatura del refrigerante: rango o funcionamiento", MEDIUM,
            "La temperatura que mide el sensor no evoluciona como debería (por ejemplo, no sube al calentar el motor).",
            listOf("Aguja de temperatura errática", "Ventilador funcionando a destiempo"),
            listOf("Sensor averiado", "Nivel de refrigerante bajo o aire en el circuito", "Conector oxidado"),
            "Compara «Refrigerante» con «Aceite» en En vivo tras unos minutos en marcha: deben subir juntas.",
        )
        add(
            "P0117", "Sensor de temperatura del refrigerante: señal baja", MEDIUM,
            "El sensor indica una temperatura imposiblemente alta, normalmente por un cortocircuito.",
            listOf("Ventilador a máxima velocidad", "Aguja de temperatura al máximo"),
            listOf("Cable del sensor en cortocircuito", "Sensor averiado"),
            "Comprueba la temperatura real antes de nada. Si el motor no está caliente, es el sensor o su cableado.",
        )
        add(
            "P0118", "Sensor de temperatura del refrigerante: señal alta", MEDIUM,
            "El sensor indica una temperatura imposiblemente baja, normalmente por circuito abierto. El motor se gestiona como si estuviera frío.",
            listOf("Consumo alto", "Ralentí acelerado", "Arranque difícil en caliente"),
            listOf("Conector desconectado u oxidado", "Cable cortado", "Sensor averiado"),
            "Revisa el conector del sensor; si está bien, cambia el sensor.",
        )
        add(
            "P0125", "Temperatura insuficiente para el control de mezcla", LOW,
            "El motor tarda demasiado en alcanzar la temperatura necesaria para regular la mezcla con la sonda lambda.",
            listOf("Calefacción que tarda en calentar", "Consumo más alto en frío"),
            listOf("Termostato que se queda abierto", "Sensor de temperatura", "Nivel de refrigerante bajo"),
            "Si el invierno es muy frío y haces trayectos cortos puede aparecer de forma puntual; si se repite, revisa el termostato.",
        )
        add(
            "P0128", "Termostato: refrigerante por debajo de la temperatura de regulación", MEDIUM,
            "El motor tarda demasiado en llegar a su temperatura de trabajo o no la alcanza. Normalmente el termostato deja " +
                "pasar el refrigerante al radiador antes de tiempo.",
            listOf("La aguja de temperatura sube despacio o no llega a 90 °C", "Calefacción tibia", "Consumo más alto"),
            listOf(
                "Termostato o módulo de gestión térmica que no cierra",
                "Nivel de refrigerante bajo",
                "Sensor de temperatura del refrigerante",
            ),
            "Compara la temperatura del refrigerante en En vivo tras 15 minutos de conducción: debería superar los 85 °C.",
        )
        add(
            "P0217", "Sobretemperatura del motor", HIGH,
            "La temperatura del motor ha superado el límite de seguridad. Seguir circulando puede deformar la culata o dañar la junta.",
            listOf("Aguja de temperatura al máximo", "Testigo de refrigerante", "Pérdida de potencia (protección)"),
            listOf(
                "Nivel de refrigerante bajo por fuga",
                "Bomba de agua defectuosa",
                "Termostato que no abre",
                "Ventilador de refrigeración que no funciona",
                "Junta de culata dañada",
            ),
            "Para en un lugar seguro y apaga el motor. No abras el depósito de refrigerante en caliente: espera a que se enfríe.",
        )
        add(
            "P2181", "Rendimiento del sistema de refrigeración", MEDIUM,
            "La temperatura del motor no evoluciona como espera la centralita: sube demasiado despacio, demasiado deprisa o no se estabiliza.",
            listOf("Temperatura irregular", "Calefacción variable", "Ventilador funcionando más de lo normal"),
            listOf(
                "Nivel de refrigerante bajo o aire en el circuito",
                "Termostato o módulo de gestión térmica",
                "Fuga de refrigerante",
                "Sensor de temperatura",
                "Ventilador de refrigeración",
            ),
            "Revisa el nivel de refrigerante con el motor frío. Si baja con el tiempo, hay una fuga que localizar.",
        )
        add(
            "P3081", "Temperatura del motor demasiado baja", LOW,
            "Código propio del grupo Volkswagen: el motor no alcanza la temperatura prevista en el tiempo esperado.",
            listOf("Calefacción tibia", "Consumo más alto en invierno"),
            listOf("Termostato o módulo de gestión térmica", "Sensor de temperatura del refrigerante", "Nivel de refrigerante bajo"),
            "Suele estar relacionado con el termostato. Si aparece junto a P0128, la causa casi siempre es la misma.",
        )
        for ((code, detail) in listOf(
            "P0597" to "circuito abierto", "P0598" to "señal baja", "P0599" to "señal alta",
        )) {
            add(
                code, "Termostato con calefacción eléctrica: $detail", MEDIUM,
                "El termostato tiene una resistencia que la centralita calienta para abrirlo antes y regular la temperatura " +
                    "según la carga. Su circuito presenta un fallo eléctrico.",
                listOf("Temperatura del motor más alta o más baja de lo normal"),
                listOf("Conector del termostato oxidado o suelto", "Resistencia del termostato averiada", "Cableado dañado"),
                "La resistencia va integrada en el termostato: si está averiada, se cambia el termostato completo.",
            )
        }
        add(
            "P0480", "Ventilador de refrigeración 1: circuito de control", MEDIUM,
            "La centralita no puede controlar correctamente el ventilador del radiador.",
            listOf("Temperatura alta en atascos", "Aire acondicionado que enfría poco parado"),
            listOf("Ventilador o su unidad de control averiados", "Fusible o relé", "Conector o cableado"),
            "Vigila la temperatura en tráfico lento hasta que lo revisen.",
        )
    }

    private fun camAndCrank() {
        val camCauses = listOf(
            "Nivel o presión de aceite bajos, o aceite viejo o de especificación incorrecta",
            "Electroválvula del variador sucia o averiada",
            "Variador del árbol de levas desgastado",
            "Distribución desfasada (correa estirada o mal montada)",
        )
        for ((code, shaft, direction) in listOf(
            Triple("P0011", "admisión", "avance excesivo o rendimiento"),
            Triple("P0012", "admisión", "retraso excesivo"),
            Triple("P0014", "escape", "avance excesivo o rendimiento"),
            Triple("P0015", "escape", "retraso excesivo"),
        )) {
            add(
                code, "Árbol de levas de $shaft (banco 1): $direction", MEDIUM,
                "El variador del árbol de levas de $shaft no llega a la posición que pide la centralita.",
                listOf("Ralentí irregular", "Pérdida de potencia", "Ruido de traqueteo al arrancar en frío"),
                camCauses,
                "Comprueba el nivel y el estado del aceite (con la norma VW correcta): es la causa más frecuente.",
            )
        }
        for ((code, shaft) in listOf("P0010" to "admisión", "P0013" to "escape")) {
            add(
                code, "Actuador del árbol de levas de $shaft (banco 1): circuito", MEDIUM,
                "Fallo eléctrico en la electroválvula que controla el variador del árbol de levas de $shaft.",
                listOf("Ralentí irregular", "Pérdida de potencia"),
                listOf("Electroválvula averiada", "Conector o cableado dañado"),
                "Revisa el conector de la electroválvula; si está bien, mide su resistencia.",
            )
        }
        for ((code, shaft) in listOf("P0016" to "admisión", "P0017" to "escape")) {
            add(
                code, "Correlación cigüeñal - árbol de levas de $shaft (banco 1)", HIGH,
                "Las señales del cigüeñal y del árbol de levas de $shaft no están sincronizadas como deberían: la " +
                    "distribución puede estar desfasada.",
                listOf("Arranque difícil", "Pérdida de potencia", "Ralentí irregular"),
                listOf(
                    "Correa de distribución estirada o que ha saltado un diente",
                    "Variador del árbol de levas defectuoso",
                    "Sensor de posición del árbol de levas o del cigüeñal",
                    "Rueda fónica dañada",
                ),
                "No fuerces el motor: si la distribución ha saltado, válvulas y pistones pueden llegar a tocarse. Llévalo al taller.",
            )
        }
        add(
            "P0335", "Sensor de posición del cigüeñal: circuito", HIGH,
            "Sin la señal del cigüeñal el motor no sabe cuándo inyectar ni cuándo dar chispa: puede calarse o no arrancar.",
            listOf("El motor se para en marcha", "No arranca", "Cuentarrevoluciones que cae a cero"),
            listOf("Sensor averiado", "Conector o cableado dañado", "Rueda fónica dañada o sucia"),
            "Si el coche se para en marcha, apártate con seguridad. Revisa el conector del sensor.",
        )
        add(
            "P0340", "Sensor del árbol de levas de admisión (banco 1): circuito", MEDIUM,
            "La centralita no recibe la señal del sensor del árbol de levas de admisión. El motor sigue funcionando con la del cigüeñal, pero peor.",
            listOf("Arranque más largo", "Pérdida de potencia"),
            listOf("Sensor averiado", "Conector o cableado dañado", "Rueda de impulsos del árbol de levas"),
            "Revisa el conector del sensor, en la culata.",
        )
        add(
            "P0341", "Sensor del árbol de levas de admisión (banco 1): rango o funcionamiento", MEDIUM,
            "La señal del sensor del árbol de levas llega, pero no cuadra con la del cigüeñal.",
            listOf("Arranque más largo", "Tirones"),
            listOf("Sensor defectuoso", "Interferencias en el cableado", "Distribución ligeramente desfasada"),
            "Si aparece junto a P0016, sospecha primero de la distribución.",
        )
        for ((code, detail) in listOf("P0365" to "circuito", "P0366" to "rango o funcionamiento")) {
            add(
                code, "Sensor del árbol de levas de escape (banco 1): $detail", MEDIUM,
                "Problema con la señal del sensor del árbol de levas de escape.",
                listOf("Arranque más largo", "Pérdida de potencia"),
                listOf("Sensor averiado", "Conector o cableado dañado"),
                "Revisa el conector del sensor en la culata.",
            )
        }
    }

    private fun fuelPressure() {
        add(
            "P0087", "Presión del rail de combustible demasiado baja", HIGH,
            "La presión de la inyección directa está por debajo de la que pide la centralita. El motor puede entrar en modo de emergencia.",
            listOf("Pérdida de potencia", "Tirones al acelerar", "Arranque difícil", "Modo de emergencia"),
            listOf(
                "Bomba de alta presión desgastada (o su empujador)",
                "Bomba de baja presión del depósito débil",
                "Filtro de combustible obstruido",
                "Sensor de presión del rail",
                "Fuga en la línea de alta presión",
            ),
            "Mira «Presión de inyección» en En vivo comparando ralentí y aceleración: si no sube al acelerar, el problema está en la alimentación.",
        )
        add(
            "P0088", "Presión del rail de combustible demasiado alta", HIGH,
            "La presión de la inyección directa supera la máxima prevista.",
            listOf("Pérdida de potencia", "Modo de emergencia"),
            listOf("Válvula reguladora de la bomba de alta presión", "Sensor de presión del rail", "Cableado del regulador"),
            "Evita aceleraciones fuertes hasta revisarlo.",
        )
        add(
            "P0089", "Regulador de presión de combustible: funcionamiento", MEDIUM,
            "La presión del rail no sigue a lo que manda la centralita a través de la válvula reguladora.",
            listOf("Tirones", "Pérdida de potencia"),
            listOf("Válvula reguladora de la bomba de alta presión", "Bomba de alta presión", "Sensor de presión del rail"),
            "Suele acabar en la sustitución de la bomba de alta presión; conviene confirmarlo midiendo la presión en vivo.",
        )
        for ((code, detail) in listOf(
            "P0190" to "circuito", "P0191" to "rango o funcionamiento", "P0192" to "señal baja", "P0193" to "señal alta",
        )) {
            add(
                code, "Sensor de presión del rail de combustible: $detail", MEDIUM,
                "La señal del sensor que mide la presión de la inyección directa no es fiable.",
                listOf("Tirones", "Pérdida de potencia", "Arranque difícil"),
                listOf("Sensor averiado", "Conector o cableado dañado", "Alimentación de 5 V de los sensores"),
                "Revisa el conector del sensor, en el rail de inyectores.",
            )
        }
        add(
            "P0627", "Bomba de combustible: circuito de control abierto", HIGH,
            "La centralita no puede controlar la bomba de combustible del depósito.",
            listOf("El motor se para o no arranca", "Pérdida de potencia"),
            listOf("Unidad de control de la bomba", "Fusible o relé", "Conector o cableado", "Bomba averiada"),
            "Si el motor se para, no insistas con el motor de arranque: llévalo al taller.",
        )
    }

    private fun boost() {
        val leakCauses = listOf(
            "Fuga en manguitos, abrazaderas o intercooler",
            "Actuador del turbo (wastegate) agarrotado o desajustado",
            "Válvula de recirculación (descarga) con fugas",
            "Filtro de aire obstruido",
            "Turbo desgastado",
            "Sensor de presión de sobrealimentación",
        )
        add(
            "P0299", "Presión de sobrealimentación insuficiente", MEDIUM,
            "El turbo no alcanza la presión que pide la centralita. El motor puede limitar la potencia para protegerse.",
            listOf("Falta de fuerza al acelerar", "Silbido o soplido anormal", "Modo de emergencia"),
            leakCauses,
            "Con el motor caliente, acelera en tercera desde bajas vueltas y mira «Presión de turbo» en En vivo.",
        )
        add(
            "P2263", "Sistema de sobrealimentación: rendimiento", MEDIUM,
            "La presión del turbo no sigue a la que pide la centralita, por exceso o por defecto.",
            listOf("Pérdida de potencia", "Tirones"),
            leakCauses,
            "Revisa primero manguitos e intercooler; después el actuador del turbo.",
        )
        add(
            "P0234", "Sobrepresión del turbo", HIGH,
            "El turbo da más presión de la permitida; la centralita corta potencia para proteger el motor.",
            listOf("Tirón brusco seguido de pérdida de potencia", "Modo de emergencia"),
            listOf("Wastegate atascado cerrado", "Actuador o varilla del wastegate", "Sensor de presión de sobrealimentación"),
            "Evita acelerar a fondo hasta revisarlo: el exceso de presión puede dañar el motor.",
        )
        add(
            "P0236", "Sensor de presión de sobrealimentación: rango o funcionamiento", MEDIUM,
            "La lectura del sensor de presión de sobrealimentación no es coherente con el resto de datos del motor.",
            listOf("Pérdida de potencia"),
            listOf("Sensor sucio (vapores de aceite)", "Fuga en el circuito de presión", "Conector dañado"),
            "Con el motor parado, la presión de sobrealimentación debe coincidir con la atmosférica.",
        )
        for ((code, detail) in listOf("P0045" to "circuito abierto", "P0046" to "rango o funcionamiento")) {
            add(
                code, "Control de la sobrealimentación (actuador): $detail", MEDIUM,
                "Fallo en el actuador que regula la presión del turbo.",
                listOf("Pérdida de potencia", "Modo de emergencia"),
                listOf("Actuador del turbo averiado", "Conector o cableado", "Varillaje agarrotado"),
                "Revisa el conector del actuador, junto al turbo.",
            )
        }
        add(
            "P2563", "Sensor de posición del control de sobrealimentación: rango o funcionamiento", MEDIUM,
            "La posición que informa el actuador del turbo no cuadra con la que ordena la centralita.",
            listOf("Pérdida de potencia"),
            listOf("Actuador del turbo desgastado", "Varillaje agarrotado por carbonilla", "Sensor de posición del actuador"),
            "Suele requerir sustituir el actuador y, a veces, calibrarlo con equipo de taller.",
        )
        add(
            "P2261", "Válvula de recirculación del turbo: fallo mecánico", MEDIUM,
            "La válvula que descarga la presión del turbo al soltar el acelerador no actúa bien.",
            listOf("Soplido al soltar el acelerador", "Falta de presión de turbo"),
            listOf("Membrana de la válvula rota", "Válvula agarrotada", "Conector de la válvula"),
            "Es una pieza económica y una avería conocida en motores turbo; revísala antes que el turbo.",
        )
        add(
            "P2279", "Fuga en el sistema de admisión de aire", MEDIUM,
            "Entra aire que el caudalímetro no mide, así que la mezcla queda pobre.",
            listOf("Ralentí irregular", "Silbido en el vano motor"),
            listOf(
                "Manguito de admisión agrietado o suelto",
                "Junta del colector de admisión",
                "Membrana del respiradero del cárter rota",
                "Tubo de vacío del servofreno",
            ),
            "Con el motor al ralentí, un silbido localiza la fuga. Mira la «Corrección larga» en En vivo.",
        )
    }

    private fun throttleAndPedal() {
        val limp = "Modo de emergencia: el coche apenas acelera"
        for ((code, sensor, detail) in listOf(
            Triple("P0121", "A", "rango o funcionamiento"), Triple("P0122", "A", "señal baja"), Triple("P0123", "A", "señal alta"),
            Triple("P0221", "B", "rango o funcionamiento"), Triple("P0222", "B", "señal baja"), Triple("P0223", "B", "señal alta"),
        )) {
            add(
                code, "Sensor de posición de la mariposa $sensor: $detail", MEDIUM,
                "La mariposa motorizada lleva dos sensores de posición que se vigilan entre sí. La señal del sensor $sensor no es válida.",
                listOf(limp, "Testigo de motor"),
                listOf("Cuerpo de mariposa averiado (los sensores van integrados)", "Conector o cableado", "Alimentación de 5 V de los sensores"),
                "Los sensores no se cambian por separado: se sustituye el cuerpo de mariposa y se hace su adaptación.",
            )
        }
        add(
            "P2135", "Correlación entre los sensores de la mariposa A y B", HIGH,
            "Los dos sensores de posición de la mariposa dan valores que no concuerdan; por seguridad, la centralita limita la potencia.",
            listOf(limp, "Testigo de motor"),
            listOf("Cuerpo de mariposa averiado", "Conector de la mariposa oxidado", "Cableado dañado"),
            "Circula con suavidad hasta el taller.",
        )
        add(
            "P2101", "Motor de la mariposa: rango o funcionamiento", HIGH,
            "La mariposa motorizada no se mueve como manda la centralita; normalmente el coche entra en modo de emergencia.",
            listOf(limp, "Ralentí irregular"),
            listOf("Cuerpo de mariposa sucio (carbonilla)", "Motor o engranajes de la mariposa desgastados", "Conector o cableado"),
            "A veces basta con limpiar la mariposa y hacer su adaptación; si el motor está desgastado, hay que cambiarla.",
        )
        add(
            "P0638", "Control del actuador de la mariposa: rango o funcionamiento", HIGH,
            "La mariposa no alcanza la posición ordenada en el tiempo esperado.",
            listOf(limp),
            listOf("Cuerpo de mariposa sucio o averiado", "Conector o cableado"),
            "Igual que P2101: limpieza y adaptación, o sustitución de la mariposa.",
        )
        add(
            "P2138", "Correlación entre los sensores del pedal del acelerador D y E", HIGH,
            "El pedal del acelerador lleva dos sensores que deben coincidir; no lo hacen y la centralita limita la potencia.",
            listOf(limp, "Respuesta errática al pisar el pedal"),
            listOf("Pedal del acelerador averiado (sensores integrados)", "Conector del pedal", "Cableado"),
            "Revisa el conector del pedal; si está bien, se cambia el pedal completo.",
        )
    }

    private fun knock() {
        add(
            "P0324", "Sistema de control de picado: error", MEDIUM,
            "La centralita no puede vigilar el picado (detonación). Por seguridad retrasa el encendido.",
            listOf("Menos potencia", "Más consumo"),
            listOf("Sensor de picado o su conector", "Gasolina de bajo octanaje", "Carbonilla en la cámara de combustión"),
            "Usa gasolina de 95 octanos como mínimo y revisa el sensor de picado.",
        )
        for ((code, detail) in listOf("P0325" to "circuito", "P0327" to "señal baja", "P0328" to "señal alta")) {
            add(
                code, "Sensor de picado 1: $detail", MEDIUM,
                "Sin la señal del sensor de picado, la centralita retrasa el encendido por seguridad: menos potencia y más consumo.",
                listOf("Menos potencia", "Más consumo"),
                listOf("Sensor averiado", "Sensor mal apretado al bloque", "Conector o cableado"),
                "El sensor debe apretarse al par exacto: si está flojo o pasado, no lee bien.",
            )
        }
    }

    private fun evap() {
        val leakCauses = listOf(
            "Tapón de la gasolina mal cerrado o con la junta dañada",
            "Tubo del sistema de vapores agrietado o suelto",
            "Válvula de purga del cánister",
            "Cánister (filtro de carbón activo) dañado",
        )
        add(
            "P0455", "Sistema EVAP: fuga grande", LOW,
            "El circuito que recoge los vapores de gasolina del depósito no es estanco. Muy a menudo es el tapón de la gasolina mal cerrado.",
            listOf("Olor a gasolina", "Normalmente ningún otro"),
            leakCauses,
            "Cierra bien el tapón hasta oír el clic, borra el código y comprueba si vuelve tras unos días.",
        )
        add(
            "P0442", "Sistema EVAP: fuga pequeña", LOW,
            "El circuito de vapores de gasolina tiene una pequeña fuga.",
            listOf("Normalmente ninguno"),
            leakCauses,
            "Empieza por el tapón de la gasolina y su junta.",
        )
        add(
            "P0456", "Sistema EVAP: fuga muy pequeña", LOW,
            "El circuito de vapores de gasolina tiene una fuga muy pequeña.",
            listOf("Normalmente ninguno"),
            leakCauses,
            "Empieza por el tapón de la gasolina y su junta.",
        )
        add(
            "P0441", "Sistema EVAP: caudal de purga incorrecto", LOW,
            "El paso de vapores del cánister al motor no es el esperado.",
            listOf("Normalmente ninguno"),
            listOf("Válvula de purga del cánister averiada", "Tubos obstruidos o sueltos", "Cánister saturado"),
            "No afecta a la conducción, pero impedirá completar el monitor EVAP.",
        )
        add(
            "P0496", "Sistema EVAP: caudal de purga alto", MEDIUM,
            "La válvula de purga del cánister deja pasar vapores cuando no debe (en VAG, válvula N80).",
            listOf("Arranque difícil justo después de repostar", "Ralentí irregular", "Mezcla rica"),
            listOf("Válvula de purga del cánister que no cierra", "Cableado de la válvula"),
            "Es una avería conocida y la válvula es económica.",
        )
        for ((code, detail) in listOf("P0458" to "señal baja", "P0459" to "señal alta")) {
            add(
                code, "Válvula de purga del cánister: circuito con $detail", LOW,
                "Fallo eléctrico en el circuito de la válvula de purga del cánister.",
                listOf("Normalmente ninguno"),
                listOf("Válvula averiada", "Conector o cableado"),
                "Revisa el conector de la válvula.",
            )
        }
    }

    private fun idleSpeedAndBrake() {
        add(
            "P0505", "Control del ralentí: fallo", MEDIUM,
            "El motor no consigue mantener el régimen de ralentí que pide la centralita.",
            listOf("Ralentí inestable", "El motor se cala al parar"),
            listOf("Mariposa sucia", "Toma de aire", "Adaptación de la mariposa perdida"),
            "Limpia la mariposa y haz su adaptación si se ha desconectado la batería.",
        )
        add(
            "P0506", "Ralentí más bajo de lo esperado", LOW,
            "El régimen de ralentí es inferior al que pide la centralita.",
            listOf("Ralentí bajo o vibraciones", "Riesgo de calado"),
            listOf("Mariposa sucia", "Carga excesiva del alternador o del aire acondicionado", "Adaptación de la mariposa"),
            "Limpia la mariposa y repite la adaptación.",
        )
        add(
            "P0507", "Ralentí más alto de lo esperado", LOW,
            "El régimen de ralentí es superior al que pide la centralita: entra más aire del previsto.",
            listOf("Ralentí acelerado"),
            listOf("Toma de aire", "Mariposa que no cierra del todo", "Respiradero del cárter"),
            "Busca tomas de aire con el motor al ralentí.",
        )
        for ((code, detail) in listOf("P0500" to "fallo", "P0501" to "rango o funcionamiento")) {
            add(
                code, "Señal de velocidad del vehículo: $detail", MEDIUM,
                "La centralita del motor no recibe una velocidad válida. En este coche la velocidad llega del ABS por la red CAN.",
                listOf("Velocímetro o control de crucero que no funcionan", "Cambios de marcha anómalos en automáticos"),
                listOf("Sensor de velocidad de rueda", "Fallo en el ABS", "Comunicación CAN"),
                "Si hay también averías en el ABS, el origen está allí.",
            )
        }
        add(
            "P0504", "Interruptor del freno: las señales A y B no coinciden", MEDIUM,
            "El interruptor del pedal de freno da dos señales que deben coincidir. Afecta al control de crucero, al Start&Stop y a la gestión del motor.",
            listOf("Control de crucero que no se activa", "Start&Stop inactivo", "Luces de freno que no se encienden o no se apagan"),
            listOf("Interruptor del pedal de freno desajustado o averiado", "Conector del interruptor"),
            "Comprueba con ayuda que las luces de freno se encienden y se apagan bien: es un tema de seguridad.",
        )
        add(
            "P0571", "Interruptor del freno A: circuito", MEDIUM,
            "Fallo eléctrico en el circuito del interruptor del pedal de freno.",
            listOf("Control de crucero inactivo", "Luces de freno anómalas"),
            listOf("Interruptor averiado", "Fusible", "Conector o cableado"),
            "Comprueba las luces de freno cuanto antes.",
        )
    }

    private fun electricalAndEcu() {
        val batteryCauses = listOf(
            "Batería debilitada o al final de su vida",
            "Alternador o su regulador",
            "Bornes o masas sueltos u oxidados",
            "Consumo parásito con el coche apagado",
        )
        add(
            "P0560", "Tensión del sistema: fallo", MEDIUM,
            "La tensión de alimentación de la centralita está fuera de lo normal.",
            listOf("Arranques débiles", "Start&Stop inactivo"),
            batteryCauses,
            "Mira «Tensión de la centralita» en En vivo: con el motor en marcha debe estar entre 13,5 y 14,8 V.",
        )
        add(
            "P0562", "Tensión del sistema baja", MEDIUM,
            "La tensión de la instalación eléctrica ha bajado del mínimo previsto.",
            listOf("Arranques débiles", "Start&Stop inactivo", "Avisos eléctricos variados"),
            batteryCauses,
            "Con el motor parado la batería debe dar unos 12,4 V o más; en marcha, 13,5-14,8 V.",
        )
        add(
            "P0563", "Tensión del sistema alta", MEDIUM,
            "La tensión de la instalación eléctrica supera el máximo previsto: el alternador carga de más.",
            listOf("Bombillas que se funden", "Avisos eléctricos"),
            listOf("Regulador del alternador averiado", "Conexión del alternador", "Batería en mal estado"),
            "Revísalo pronto: una sobretensión mantenida daña la electrónica.",
        )
        for ((code, line) in listOf("P0641" to "A", "P0651" to "B")) {
            add(
                code, "Tensión de referencia de sensores $line: circuito", HIGH,
                "La alimentación de 5 V que la centralita da a varios sensores está fuera de rango: pueden fallar varios sensores a la vez.",
                listOf("Varias averías de sensores simultáneas", "Modo de emergencia"),
                listOf(
                    "Un sensor en cortocircuito que arrastra la línea",
                    "Cableado dañado o pellizcado",
                    "Centralita del motor (poco frecuente)",
                ),
                "Desconectando los sensores de uno en uno se localiza el que provoca el cortocircuito.",
            )
        }
        for ((code, detail) in listOf(
            "P0601" to "error de suma de comprobación de la memoria",
            "P0604" to "error de memoria RAM",
            "P0606" to "error del procesador",
        )) {
            add(
                code, "Centralita del motor: $detail", HIGH,
                "La centralita del motor ha detectado un error interno.",
                listOf("Testigo de motor", "Funcionamiento anómalo o modo de emergencia"),
                listOf("Fallo interno de la centralita", "Alimentación o masas defectuosas", "Programación interrumpida"),
                "Antes de culpar a la centralita, comprueba batería, alimentación y masas. Si persiste, es trabajo de taller.",
            )
        }
        add(
            "P0700", "La centralita del cambio pide encender el testigo", MEDIUM,
            "El cambio automático tiene una avería guardada y pide a la centralita del motor que encienda el testigo. " +
                "El detalle está en la centralita del cambio (dirección 02), que se leerá en el escaneo completo.",
            listOf("Cambios bruscos", "Modo de emergencia del cambio"),
            listOf("Ver las averías de la centralita del cambio"),
            "Este código solo avisa: la avería concreta está en el cambio.",
        )
    }

    private fun oilPressure() {
        add(
            "P0520", "Sensor o interruptor de presión de aceite: circuito", MEDIUM,
            "La centralita no recibe una señal válida del sensor de presión de aceite, así que no puede avisarte si la presión cae.",
            listOf("Testigo de presión de aceite encendido o parpadeando"),
            listOf("Sensor o interruptor averiado", "Conector o cableado"),
            "Comprueba el nivel de aceite con la varilla o en el cuadro antes de seguir circulando.",
        )
        add(
            "P0521", "Sensor de presión de aceite: rango o funcionamiento", HIGH,
            "La presión de aceite medida no es coherente con el régimen y la temperatura del motor.",
            listOf("Testigo de presión de aceite"),
            listOf("Nivel de aceite bajo", "Aceite de viscosidad incorrecta", "Sensor defectuoso", "Bomba de aceite"),
            "Comprueba el nivel de aceite de inmediato. Si es correcto y el testigo sigue encendido, no circules.",
        )
        add(
            "P0524", "Presión de aceite del motor demasiado baja", HIGH,
            "La presión de aceite está por debajo del mínimo. Sin presión, el motor se daña en segundos.",
            listOf("Testigo rojo de aceite", "Ruidos metálicos del motor"),
            listOf("Nivel de aceite muy bajo", "Bomba de aceite o su regulación", "Filtro de aceite obstruido", "Sensor defectuoso"),
            "Para el motor en cuanto sea seguro y comprueba el nivel. No vuelvas a arrancarlo si el nivel es correcto y el aviso continúa.",
        )
    }

    private fun network() {
        val modules = listOf(
            Triple("U0100", "la centralita del motor", MEDIUM),
            Triple("U0101", "la centralita del cambio", MEDIUM),
            Triple("U0121", "la centralita del ABS", MEDIUM),
            Triple("U0140", "la electrónica de carrocería", MEDIUM),
            Triple("U0151", "la centralita del airbag", MEDIUM),
            Triple("U0155", "el cuadro de instrumentos", LOW),
            Triple("U0164", "el climatizador", LOW),
        )
        for ((code, module, severity) in modules) {
            add(
                code, "Sin comunicación con $module", severity,
                "Otra centralita ha dejado de recibir mensajes de $module por la red CAN. Puede ser algo puntual (batería " +
                    "baja al arrancar, trabajos en el coche) o un problema de cableado o de la propia centralita.",
                listOf("Avisos en el cuadro relacionados con ese sistema", "A veces ninguno"),
                listOf(
                    "Tensión de batería baja (arranques, batería débil)",
                    "Batería desconectada recientemente",
                    "Conector o cableado de la red CAN",
                    "Centralita sin alimentación (fusible) o averiada",
                ),
                "Si apareció tras cambiar o desconectar la batería, bórralo y comprueba si vuelve.",
            )
        }
        add(
            "U0001", "Bus CAN de alta velocidad: fallo de comunicación", MEDIUM,
            "La red principal por la que se comunican las centralitas tiene un fallo.",
            listOf("Varios avisos a la vez en el cuadro", "Sistemas que dejan de funcionar"),
            listOf("Cableado de la red CAN dañado", "Una centralita que bloquea la red", "Tensión de batería baja"),
            "Si hay muchos avisos a la vez, empieza por la batería y las masas.",
        )
        for ((code, module) in listOf("U0401" to "la centralita del motor", "U0415" to "el ABS")) {
            add(
                code, "Datos no válidos recibidos de $module", MEDIUM,
                "Llegan mensajes de $module, pero con datos fuera de rango o marcados como no válidos.",
                listOf("Avisos relacionados con ese sistema"),
                listOf("Avería en $module (revisa sus códigos)", "Software incompatible tras una actualización", "Tensión de batería baja"),
                "Lee las averías de $module: el origen suele estar allí.",
            )
        }
    }
}
