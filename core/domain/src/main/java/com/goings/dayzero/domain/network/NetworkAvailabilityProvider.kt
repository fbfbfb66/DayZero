package com.goings.dayzero.domain.network

/**
 * Abstraction over platform network reachability.
 *
 * Implementations must not perform blocking I/O inside [hasValidatedInternet];
 * the method is expected to be called on the caller's thread (usually the main
 * thread before a user-initiated send action).
 */
fun interface NetworkAvailabilityProvider {
    fun hasValidatedInternet(): Boolean
}
