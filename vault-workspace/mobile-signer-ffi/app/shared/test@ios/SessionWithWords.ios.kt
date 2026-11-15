package com.mebit.app.data

/** The iOS test links the native library, so the real offline restore makes the session. */
internal actual fun sessionWithWords(words: List<String>, passphrase: String): OnboardingSession =
    OnboardingSession().apply { restoreFromWords(words, passphrase) }
