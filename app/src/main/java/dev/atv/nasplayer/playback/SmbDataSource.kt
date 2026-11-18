package dev.atv.nasplayer.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.readAheadAsync
import dev.atv.nasplayer.source.SmbBrowser
import dev.atv.nasplayer.source.SmbSessionPool
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Media3 DataSource reading `smb://<serverId>/<share>/<path>` with several reads in flight,
 * so throughput is bounded by the link rather than by round-trip latency.
 */
@UnstableApi
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var file: File? = null
    private var endOffset = 0L          // exclusive
    private var nextRequestOffset = 0L
    private var bytesRemaining = 0L
    private val inFlight = ArrayDeque<Pair<Int, Future<SMB2ReadResponse>>>()
    private var chunkSize = DEFAULT_CHUNK
    private var buffer: ByteArray = EMPTY
    private var bufferPos = 0
    private var bufferLen = 0
    private var opened = false
    private var openedAt = 0L
    private var bytesRead = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val serverId = dataSpec.uri.host ?: throw IOException("Missing server")
        val (shareName, rel) = SmbBrowser.splitSmbPath(dataSpec.uri.path ?: "/")
        if (shareName == null) throw IOException("Missing share")

        val f = try {
            try {
                openFile(serverId, shareName, rel)
            } catch (e: Exception) {
                if (!SmbSessionPool.isConnectionError(e)) throw e
                SmbSessionPool.invalidate(serverId)
                openFile(serverId, shareName, rel)
            }
        } catch (e: Exception) {
            throw asDataSourceException(e)
        }
        file = f
        val fileSize = smb { f.fileInformation.standardInformation.endOfFile }
        if (dataSpec.position > fileSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, fileSize - dataSpec.position)
        } else {
            fileSize - dataSpec.position
        }
        nextRequestOffset = dataSpec.position
        endOffset = dataSpec.position + bytesRemaining
        val maxRead = f.diskShare.treeConnect.session.connection.negotiatedProtocol.maxReadSize
        chunkSize = maxRead.coerceIn(64 * 1024, DEFAULT_CHUNK)
        opened = true
        openedAt = android.os.SystemClock.elapsedRealtime()
        bytesRead = 0
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun openFile(serverId: String, share: String, rel: String): File =
        SmbSessionPool.share(serverId, share).openFile(
            rel,
            setOf(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (bufferPos >= bufferLen && !fillBuffer()) return C.RESULT_END_OF_INPUT
        val n = minOf(length.toLong(), (bufferLen - bufferPos).toLong(), bytesRemaining).toInt()
        System.arraycopy(buffer, bufferPos, target, offset, n)
        bufferPos += n
        bytesRemaining -= n
        bytesRead += n
        bytesTransferred(n)
        return n
    }

    private fun schedule() {
        val f = file ?: return
        while (inFlight.size < READ_AHEAD && nextRequestOffset < endOffset) {
            val len = minOf(chunkSize.toLong(), endOffset - nextRequestOffset).toInt()
            inFlight.add(len to smb { f.readAheadAsync(nextRequestOffset, len) })
            nextRequestOffset += len
        }
    }

    private fun fillBuffer(): Boolean {
        schedule()
        val (requested, future) = inFlight.pollFirst() ?: return false
        val resp = try {
            future.get(READ_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        }
        val status = resp.header.statusCode
        if (status == NtStatus.STATUS_END_OF_FILE.value) return false
        if (!NtStatus.isSuccess(status)) {
            throw DataSourceException(
                IOException("SMB read failed: 0x" + java.lang.Long.toHexString(status)),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        }
        val got = resp.dataLength
        if (got <= 0) return false
        buffer = resp.data
        bufferPos = 0
        bufferLen = got
        if (got < requested) {
            // Short read: requests already in flight were issued for the wrong offsets.
            val resumeAt = nextRequestOffset - inFlight.sumOf { it.first.toLong() } - (requested - got)
            drain()
            nextRequestOffset = resumeAt
        }
        schedule()
        return true
    }

    /**
     * Forgets the reads in flight without waiting for them (smbj futures can't be cancelled). Their
     * replies still arrive and smbj discards them, so a seek or close on a slow link doesn't stall.
     */
    private fun drain() = inFlight.clear()

    /** smbj throws unchecked [SMBRuntimeException]s; Media3 only handles IOExceptions. */
    private inline fun <T> smb(block: () -> T): T = try {
        block()
    } catch (e: SMBRuntimeException) {
        throw asDataSourceException(e)
    }

    private fun asDataSourceException(e: Exception): DataSourceException {
        val status = (e as? SMBApiException)?.status
        val code = when (status) {
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE, NtStatus.STATUS_NOT_FOUND, NtStatus.STATUS_DELETE_PENDING,
            NtStatus.STATUS_BAD_NETWORK_NAME -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            NtStatus.STATUS_ACCESS_DENIED -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            null -> if (e is DataSourceException) return e else PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            else -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        }
        return DataSourceException(e, code)
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened && bytesRead > 4_000_000) {
            val ms = (android.os.SystemClock.elapsedRealtime() - openedAt).coerceAtLeast(1)
            android.util.Log.i("NasSmb", "read ${bytesRead / 1_000_000} MB in $ms ms = ${bytesRead * 8 / ms / 1000} Mbit/s (chunk=${chunkSize / 1024} KiB x$READ_AHEAD)")
        }
        uri = null
        try {
            drain()
            file?.let { runCatching { it.close() } }
        } finally {
            file = null
            buffer = EMPTY
            bufferPos = 0
            bufferLen = 0
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        private const val DEFAULT_CHUNK = 1024 * 1024
        private const val READ_AHEAD = 4
        private const val READ_TIMEOUT_S = 30L
        private val EMPTY = ByteArray(0)
    }
}
