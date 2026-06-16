package com.example.dink_smb_player.data.source.smb

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.example.dink_smb_player.data.source.cloud.CloudDataSource

/**
 * Switches between [DefaultDataSource] (file:// / content:// / http(s)://),
 * [SmbDataSource] (smb://) and [CloudDataSource] (gdrive://) at `open()` time.
 * ExoPlayer only calls [DataSource.Factory.createDataSource] once per MediaItem,
 * so the dispatch has to happen on the live DataSource — not on the factory.
 */
class DinkDataSourceFactory(context: Context) : DataSource.Factory {

    private val ctx = context.applicationContext

    override fun createDataSource(): DataSource = MultiSchemeDataSource(ctx)

    private class MultiSchemeDataSource(context: Context) : DataSource {
        private val default: DataSource = DefaultDataSource.Factory(context).createDataSource()
        private val smb: DataSource = SmbDataSource()
        private val cloud: DataSource = CloudDataSource()
        private var active: DataSource = default
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            default.addTransferListener(transferListener)
            smb.addTransferListener(transferListener)
            cloud.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            active = when (dataSpec.uri.scheme?.lowercase()) {
                "smb" -> smb
                "gdrive" -> cloud
                else -> default
            }
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active.read(buffer, offset, length)

        override fun getUri(): Uri? = active.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

        override fun close() = active.close()
    }
}
