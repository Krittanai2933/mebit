package com.mebit.app.data

// shortcut: reflection on OnboardingSession's private setters, because restoreFromWords needs the native library the host JVM lacks. Replace with an internal test constructor once OnboardingSession may change.
internal actual fun sessionWithWords(words: List<String>, passphrase: String): OnboardingSession {
    val session = OnboardingSession()
    OnboardingSession::class.java.getDeclaredMethod("setWords", List::class.java).apply { isAccessible = true }.invoke(session, words)
    OnboardingSession::class.java.getDeclaredMethod("setPassphrase", String::class.java).apply { isAccessible = true }.invoke(session, passphrase)
    return session
}
