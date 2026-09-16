package com.chessecho.web

/**
 * Explicit opt-in marker for endpoints that support both authenticated and
 * guest requests. A nullable principal without this marker remains required
 * by the fail-closed resolver.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class OptionalAuthenticatedPrincipal
