package app.alcovaibe.vkmusicexport.music

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Fixed categories only. Never retain a Throwable, its message, cause, URL or stack. */
internal fun networkFailure(error: IOException): MusicFailure {
    val kind=when(error) {
        is SSLException->MusicFailureKind.TLS
        is SocketTimeoutException,is InterruptedIOException->MusicFailureKind.TIMEOUT
        is UnknownHostException->MusicFailureKind.DNS
        is ConnectException->MusicFailureKind.CONNECT
        is SocketException->MusicFailureKind.SOCKET
        is ProtocolException->MusicFailureKind.PROTOCOL
        is EOFException->MusicFailureKind.EOF
        else->MusicFailureKind.IO
    }
    return MusicFailure(if(kind==MusicFailureKind.TLS)MusicError.TLS else MusicError.NETWORK,kind=kind)
}
