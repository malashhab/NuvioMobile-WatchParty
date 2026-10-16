package com.nuvio.app.features.autosync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.Volatile
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_queue_create

/**
 * Whether the phone is on Wi-Fi (or wired) right now, so sync to audio only reads ahead of
 * playback there unless mobile data is allowed. Started at app launch, since the first update
 * arrives asynchronously.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosNetworkPath {
    /** null while offline or before the first update. */
    @Volatile var onWifi: Boolean? = null
        private set

    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            onWifi = when {
                nw_path_get_status(path) != nw_path_status_satisfied -> null
                else -> nw_path_uses_interface_type(path, nw_interface_type_wifi) ||
                    nw_path_uses_interface_type(path, nw_interface_type_wired)
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.nuvio.autosync-network-path", null))
        nw_path_monitor_start(monitor)
    }
}
