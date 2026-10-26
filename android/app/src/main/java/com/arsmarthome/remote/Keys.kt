package com.arsmarthome.remote

/**
 * Astrion HA100 hardware keys. Codes are what the remote's firmware delivers
 * as ordinary Android KeyEvents (see docs/PROTOCOL.md section 5).
 */
enum class Key(vararg val codes: Int) {
    UP(19), DOWN(20), LEFT(21), RIGHT(22), CENTER(23, 66),
    BACK(4), MAIN(82), HOME(131, 3), POWER(132), VOICE(133),
    REWIND(134), PLAY(135), STOP(136), FASTFORWARD(137),
    RED_BUTTON(138), GREEN_BUTTON(139), BLUE_BUTTON(140), YELLOW_BUTTON(141),
    VOLUME_UP(24), VOLUME_DOWN(25), MUTE(164, 91),
    PAGE_UP(92), PAGE_DOWN(93),
    UNKNOWN;

    val isDpad: Boolean get() = this == UP || this == DOWN || this == LEFT || this == RIGHT || this == CENTER
    val isVolume: Boolean get() = this == VOLUME_UP || this == VOLUME_DOWN || this == MUTE
    val isColor: Boolean get() = this == RED_BUTTON || this == GREEN_BUTTON || this == BLUE_BUTTON || this == YELLOW_BUTTON
    val isTransport: Boolean get() = this == REWIND || this == PLAY || this == STOP || this == FASTFORWARD

    /** Held keys that should keep firing while held. */
    val repeats: Boolean get() = isVolume && this != MUTE || isDpad && this != CENTER

    companion object {
        private val byCode: Map<Int, Key> = values()
            .flatMap { key -> key.codes.map { it to key } }
            .toMap()

        fun from(code: Int): Key = byCode[code] ?: UNKNOWN
    }
}
