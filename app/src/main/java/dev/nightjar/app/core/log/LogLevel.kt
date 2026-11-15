package dev.nightjar.app.core.log

/** Log level, `E`, `W`, `I`, `D`. */
enum class LogLevel(
    val letter: Char,
) {
    E('E'),
    W('W'),
    I('I'),
    D('D'),
    ;

    /** True when a line at this level is written to logcat in a release build (: W, E only). */
    val goesToLogcatInRelease: Boolean get() = this == E || this == W
}
