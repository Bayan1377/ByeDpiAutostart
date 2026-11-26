package ru.dsh.byediautostart

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Отдаёт APK по локальной Wi-Fi сети, чтобы его можно было передать по QR-ссылке.
 * Сервер живёт, пока открыт диалог с QR-кодом.
 */
class QrShareServer(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    var url: String? = null
        private set

    fun start(): String? {
        val ip = localIpv4() ?: return null
        val apk = File(context.applicationInfo.sourceDir)
        if (!apk.exists()) return null

        val socket = try {
            ServerSocket(0, 4)
        } catch (e: Exception) {
            return null
        }
        serverSocket = socket
        url = "http://$ip:${socket.localPort}/$FILE_NAME"
        Log.i("QrShareServer", "APK URL: $url")

        worker = Thread {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    Thread {
                        try {
                            serve(client, apk)
                        } catch (e: Exception) {
                            Log.w("QrShareServer", "client failed", e)
                        }
                    }.also { it.isDaemon = true; it.start() }
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w("QrShareServer", "accept failed", e)
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "qr-apk-server"
            it.start()
        }
        return url
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // уже закрыт
        }
        serverSocket = null
        worker?.interrupt()
        worker = null
    }

    private fun serve(client: Socket, apk: File) {
        client.use { socket ->
            socket.soTimeout = 8_000
            val reader = socket.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }

            val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            val out = socket.getOutputStream()
            if (path != "/" && path != "/$FILE_NAME") {
                out.write(
                    ("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                return
            }

            val length = apk.length()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/vnd.android.package-archive\r\n")
                append("Content-Length: $length\r\n")
                append("Content-Disposition: attachment; filename=\"$FILE_NAME\"\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray())
            apk.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
            out.flush()
        }
    }

    private fun localIpv4(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    companion object {
        private const val FILE_NAME = "ByeDpiAutostart.apk"

        fun qrBitmap(text: String, size: Int = 720): Bitmap {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            return bitmap
        }
    }
}
