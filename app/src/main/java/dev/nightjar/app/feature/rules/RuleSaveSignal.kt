package dev.nightjar.app.feature.rules

/**
 * One-shot hand-off from "a rule was just saved".
 *
 * The editor closes as soon as the repository write succeeds, so a snackbar shown on the editor would disappear with
 * it. Only a save that returns to Custom rules is announced: the rules list arms the signal before it opens the editor
 * ([expect]), so a save started from Messages, Detail or Firewall is dropped instead of replaying here much later.
 */
object RuleSaveSignal {
    @Volatile private var expecting = false

    @Volatile private var pending = false

    /** Called by Custom rules just before it opens Rule editor. */
    fun expect() {
        expecting = true
        pending = false
    }

    /** Called by Rule editor on a successful write; ignored unless Custom rules is the screen the editor returns to. */
    fun post() {
        if (expecting) pending = true
        expecting = false
    }

    /**
     * True once per expected save. Also drops a stale expectation left by an editor the user closed without saving,
     * which is what Custom rules calls on every return to the list.
     */
    fun consume(): Boolean {
        expecting = false
        if (!pending) return false
        pending = false
        return true
    }
}
