package com.sistemadiagnosis.core.elm

/** Protocolos OBD con su número en el ELM327 (ATSPx / ATDPN). */
enum class ObdProtocol(val code: Char, val displayName: String, val isCan: Boolean) {
    SAE_J1850_PWM('1', "SAE J1850 PWM (41.6 kbaud)", false),
    SAE_J1850_VPW('2', "SAE J1850 VPW (10.4 kbaud)", false),
    ISO_9141_2('3', "ISO 9141-2 (5 baud init)", false),
    ISO_14230_4_KWP_5BAUD('4', "ISO 14230-4 KWP (5 baud init)", false),
    ISO_14230_4_KWP_FAST('5', "ISO 14230-4 KWP (fast init)", false),
    ISO_15765_4_CAN_11_500('6', "ISO 15765-4 CAN (11 bit, 500 kbaud)", true),
    ISO_15765_4_CAN_29_500('7', "ISO 15765-4 CAN (29 bit, 500 kbaud)", true),
    ISO_15765_4_CAN_11_250('8', "ISO 15765-4 CAN (11 bit, 250 kbaud)", true),
    ISO_15765_4_CAN_29_250('9', "ISO 15765-4 CAN (29 bit, 250 kbaud)", true),
    SAE_J1939_CAN('A', "SAE J1939 CAN (29 bit, 250 kbaud)", true),
    USER1_CAN('B', "USER1 CAN", true),
    USER2_CAN('C', "USER2 CAN", true);

    companion object {
        fun fromCode(code: Char): ObdProtocol? = entries.firstOrNull { it.code == code.uppercaseChar() }
    }
}
