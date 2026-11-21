package com.drdisagree.teledrive.desktop.startup

/**
 * Round 11: a thread-safe lazy holder for an expensive object that must
 * not be created until first real use. [create] runs at most once, on
 * the first [get] call; [isCreated] lets callers (and tests) prove the
 * object does not exist yet.
 *
 * Used by the transfer-bubble overlay for the shared TransfersStore:
 * the store (and its eight DAO flows) is only built when transfer
 * activity is observed or a widget control is actually pressed.
 */
class LazyValue<T : Any>(private val create: () -> T) {

    @Volatile
    private var instance: T? = null

    val isCreated: Boolean get() = instance != null

    fun get(): T =
        instance ?: synchronized(this) {
            instance ?: create().also { instance = it }
        }
}
