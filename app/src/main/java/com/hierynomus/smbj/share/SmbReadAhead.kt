// Lives in smbj's package so it can reach File.readAsync, which smbj keeps package-private.
// Pipelining reads is what makes high-bitrate (4K remux) playback over SMB smooth.
package com.hierynomus.smbj.share

import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import java.util.concurrent.Future

fun File.readAheadAsync(offset: Long, length: Int): Future<SMB2ReadResponse> = readAsync(offset, length)
