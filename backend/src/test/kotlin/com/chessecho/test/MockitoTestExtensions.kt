package com.chessecho.test

import org.mockito.invocation.InvocationOnMock

/**
 * Reified type-safe argument accessor for Mockito [InvocationOnMock].
 * Resolves arguments without triggering unchecked CastNullableToNonNullableType warnings.
 */
inline fun <reified T> InvocationOnMock.arg(index: Int): T = getArgument(index, T::class.java)
