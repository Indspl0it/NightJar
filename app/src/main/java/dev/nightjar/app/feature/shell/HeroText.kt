package dev.nightjar.app.feature.shell

import dev.nightjar.app.domain.protection.ProtectionCopy
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.domain.text.Templates

/**
 * The title and the body of the protection hero for [report] (Health hero and the global banner).
 *
 * [simNames] is the SIM part of the copy ("SIM 1", "SIM 1 and SIM 2" or "your SIMs").
 */
internal fun Strings.heroText(
    report: ProtectionReport,
    simNames: String,
): Pair<String, String> {
    val hero = ProtectionCopy.hero(report = report, subNames = simNames)
    val title = Templates.format(get(hero.titleKey), hero.titleArgs)
    val body = Templates.format(get(hero.subtitleKey), hero.subtitleArgs)
    return title to body
}
