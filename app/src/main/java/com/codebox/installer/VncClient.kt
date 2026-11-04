package com.codebox.installer

import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class VncClient(private val onFrame: () -> Unit, private val onClosed: (String) -> Unit) {
    val lock = Any()
    @Volatile var bitmap: Bitmap? = null
    @Volatile private var running = false
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val sender = Executors.newSingleThreadExecutor()

    fun connect(port: Int) {
        running = true
        Thread { loop(port) }.start()
    }

    private fun send(b: ByteArray) {
        val o = out ?: return
        sender.execute {
            try {
                synchronized(o) { o.write(b); o.flush() }
            } catch (e: Exception) {
            }
        }
    }

    private fun req(inc: Int, w: Int, h: Int): ByteArray = byteArrayOf(
        3, inc.toByte(), 0, 0, 0, 0,
        (w shr 8).toByte(), w.toByte(), (h shr 8).toByte(), h.toByte()
    )

    fun pointer(mask: Int, x: Int, y: Int) {
        send(byteArrayOf(5, mask.toByte(), (x shr 8).toByte(), x.toByte(), (y shr 8).toByte(), y.toByte()))
    }

    fun key(sym: Int, down: Boolean) {
        send(byteArrayOf(4, if (down) 1 else 0, 0, 0,
            (sym shr 24).toByte(), (sym shr 16).toByte(), (sym shr 8).toByte(), sym.toByte()))
    }

    private fun loop(port: Int) {
        try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", port), 3000)
            s.tcpNoDelay = true
            socket = s
            val inp = DataInputStream(s.getInputStream().buffered(65536))
            val o = DataOutputStream(s.getOutputStream())
            val ver = ByteArray(12); inp.readFully(ver)
            o.write("RFB 003.008\n".toByteArray()); o.flush()
            val n = inp.readUnsignedByte()
            if (n == 0) throw Exception("server refused")
            val types = ByteArray(n); inp.readFully(types)
            o.write(1); o.flush()
            if (inp.readInt() != 0) throw Exception("security failed")
            o.write(1); o.flush()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            inp.readFully(ByteArray(16))
            val nameLen = inp.readInt()
            inp.readFully(ByteArray(nameLen))

            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bm
            out = o

            val pf = ByteArray(20)
            pf[4] = 32; pf[5] = 24; pf[6] = 0; pf[7] = 1
            pf[9] = 255.toByte(); pf[11] = 255.toByte(); pf[13] = 255.toByte()
            pf[14] = 16; pf[15] = 8; pf[16] = 0
            send(pf)
            send(byteArrayOf(2, 0, 0, 1, 0, 0, 0, 0))
            send(req(0, w, h))

            var buf = ByteArray(0)
            var ints = IntArray(0)
            while (running) {
                when (inp.readUnsignedByte()) {
                    0 -> {
                        inp.readUnsignedByte()
                        val nr = inp.readUnsignedShort()
                        for (r in 0 until nr) {
                            val x = inp.readUnsignedShort()
                            val y = inp.readUnsignedShort()
                            val rw = inp.readUnsignedShort()
                            val rh = inp.readUnsignedShort()
                            val enc = inp.readInt()
                            if (enc != 0) throw Exception("encoding $enc")
                            val len = rw * rh * 4
                            if (buf.size < len) { buf = ByteArray(len); ints = IntArray(rw * rh) }
                            if (ints.size < rw * rh) ints = IntArray(rw * rh)
                            inp.readFully(buf, 0, len)
                            for (i in 0 until rw * rh) {
                                val j = i * 4
                                ints[i] = (0xFF shl 24) or
                                    ((buf[j + 2].toInt() and 0xFF) shl 16) or
                                    ((buf[j + 1].toInt() and 0xFF) shl 8) or
                                    (buf[j].toInt() and 0xFF)
                            }
                            synchronized(lock) { bm.setPixels(ints, 0, rw, x, y, rw, rh) }
                        }
                        onFrame()
                        send(req(1, w, h))
                    }
                    1 -> {
                        inp.readUnsignedByte(); inp.readUnsignedShort()
                        val cnt = inp.readUnsignedShort()
                        inp.readFully(ByteArray(cnt * 6))
                    }
                    2 -> {}
                    3 -> {
                        inp.readFully(ByteArray(3))
                        val l = inp.readInt()
                        inp.readFully(ByteArray(l))
                    }
                    else -> throw Exception("unknown message")
                }
            }
        } catch (e: Exception) {
            if (running) onClosed(e.message ?: "closed")
        }
    }

    fun close() {
        running = false
        try { socket?.close() } catch (e: Exception) {}
        sender.shutdown()
    }
}
