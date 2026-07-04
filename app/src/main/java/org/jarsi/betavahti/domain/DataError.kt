package org.jarsi.betavahti.domain

/** Datakerroksen virheet domain-muodossa; alustavirheet eivät vuoda ViewModeliin. */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Local(cause: Throwable) : DataError("Local storage error", cause)
    class Scan(cause: Throwable) : DataError("Package scan error", cause)
}
