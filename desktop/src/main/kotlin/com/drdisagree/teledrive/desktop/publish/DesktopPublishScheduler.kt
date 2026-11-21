package com.drdisagree.teledrive.desktop.publish

import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.publish.PublishRunOutcome
import com.drdisagree.teledrive.core.publish.PublishRunState
import com.drdisagree.teledrive.core.publish.PublishScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Desktop publish scheduler: a thin adapter from the Koin-provided
 * [PublishOutboxDrainer] to [PublishRunLoop], which owns the kick /
 * retry / cancel contract and the visible run state (PC-32).
 */
class DesktopPublishScheduler(
    drainer: PublishOutboxDrainer,
    dispatchers: DispatcherProvider
) : PublishScheduler {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val loop = PublishRunLoop(
        drain = PublishDrain { isStopped, onProgress, onFailure ->
            drainer.drain(
                isStopped = isStopped,
                onProgress = onProgress,
                onFailure = onFailure
            )
        },
        scope = scope
    )

    override fun kick() = loop.kick()

    override fun cancel() = loop.cancel()

    override val runState: StateFlow<PublishRunState> get() = loop.runState

    override val lastOutcome: StateFlow<PublishRunOutcome?> get() = loop.lastOutcome

    override val lastFinishedAtMs: StateFlow<Long?> =
        loop.lastFinishedAt
            .map { it?.toEpochMilli() }
            .stateIn(scope, SharingStarted.Eagerly, null)
}
