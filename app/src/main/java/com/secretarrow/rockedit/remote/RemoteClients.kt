package com.secretarrow.rockedit.remote

import android.content.Context
import com.secretarrow.rockedit.core.App
import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.UsbOtgLogic

/**
 * Single entry point used by the remote browser and the content provider to
 * obtain a [RemoteClient] for a connection id. Centralizes the v0.15.0 USB
 * OTG sentinel branch so both call sites behave identically:
 *
 *  - normal id: load + decrypt the saved connection, build via the factory;
 *  - [UsbOtgLogic.SENTINEL_CONNECTION_ID]: return the open USB session;
 *  - unknown id: null (callers show their standard "open failed" path).
 */
object RemoteClients {
    fun open(
        context: Context,
        connectionId: Long,
    ): RemoteClient? {
        if (connectionId == UsbOtgLogic.SENTINEL_CONNECTION_ID) {
            return UsbOtgSupport.Session.client
                ?: throw IllegalStateException("the USB OTG session is closed — reopen the device from the Storage Manager")
        }
        val connection = App.remoteConnections(context).find(connectionId) ?: return null
        return RemoteClientFactory.create(context, connection)
    }
}
