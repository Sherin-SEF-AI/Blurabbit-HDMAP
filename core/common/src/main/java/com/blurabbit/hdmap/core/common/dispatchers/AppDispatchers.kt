package com.blurabbit.hdmap.core.common.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable dispatcher bundle so coroutine code never references [Dispatchers] directly —
 * makes every component deterministically testable with the kotlinx-coroutines-test dispatchers.
 */
data class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
)
