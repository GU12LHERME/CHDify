package com.conversorchd

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.conversorchd.core.Analyzer
import com.conversorchd.core.CompressionParams
import com.conversorchd.core.Profile
import com.conversorchd.core.DiscMode
import com.conversorchd.core.InputFile
import com.conversorchd.core.Job
import com.conversorchd.core.NativeResult
import com.conversorchd.core.ResultCodes
import com.conversorchd.core.SourceKind
import com.conversorchd.core.TrackRef
import com.conversorchd.core.WorkDir
import com.conversorchd.nativebridge.NativeChd
import com.conversorchd.nativebridge.NativeListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin
import kotlin.random.Random

/**
 * Testes de conversão real no dispositivo/emulador, usando a biblioteca nativa
 * empacotada no APK e o mesmo caminho de código do serviço (descritores de
 * arquivo, diretório de trabalho com espelhos esparsos, verificação).
 */
@RunWith(AndroidJUnit4::class)
class ConversionInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(ctx.cacheDir, "teste-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ utilidades

    private class Recorder(private val cancelAt: Double = -1.0) : NativeListener {
        var calls = 0
        var last = 0.0
        val logs = StringBuilder()
        var written = 0L
        override fun onProgress(stage: Int, fraction: Double, ratio: Double, done: Long, total: Long, written: Long) {
            calls++
            if (written > 0) this.written = written
            last = fraction
            if (cancelAt >= 0 && fraction >= cancelAt) NativeChd.cancel()
        }
        override fun onLog(message: String) {
            logs.append(message).append('\n')
        }
    }

    /** Imagem de 2048 bytes/setor com um descritor ISO9660 mínimo e dados mistos. */
    private fun makeIso(name: String, sectors: Int, seed: Int = 1): File {
        val f = File(dir, name)
        val rnd = Random(seed)
        RandomAccessFile(f, "rw").use { raf ->
            val sector = ByteArray(2048)
            for (i in 0 until sectors) {
                when {
                    i == 16 -> {
                        sector.fill(0)
                        sector[0] = 1
                        "CD001".toByteArray().copyInto(sector, 1)
                        sector[6] = 1
                        "TESTE_ANDROID".toByteArray().copyInto(sector, 40)
                        // tamanho do volume (little-endian) em 80
                        for (b in 0 until 4) sector[80 + b] = ((sectors shr (8 * b)) and 0xff).toByte()
                    }
                    i == 17 -> { sector.fill(0); sector[0] = 255.toByte(); "CD001".toByteArray().copyInto(sector, 1) }
                    i % 3 == 0 -> rnd.nextBytes(sector)
                    else -> sector.fill((i and 0xff).toByte())
                }
                raf.write(sector)
            }
        }
        return f
    }

    private fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun convertDvd(
        input: File,
        output: File,
        listener: NativeListener? = null,
        params: CompressionParams = CompressionParams("", 0),
    ): NativeResult {
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { inPfd ->
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE).use { outPfd ->
                return NativeResult.parse(NativeChd.convertDvd(inPfd.fd, outPfd.fd, 0, params.hunkBytes, params.codecs, listener))
            }
        }
    }

    private fun verify(chd: File, threads: Int = 0): NativeResult =
        ParcelFileDescriptor.open(chd, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            NativeResult.parse(NativeChd.verify(pfd.fd, threads, null))
        }

    private fun input(f: File) = InputFile(Uri.fromFile(f), f.name, f.length())

    // ------------------------------------------------------------------ testes

    @Test
    fun coreLoads() {
        assertTrue(NativeChd.coreVersion().contains("ConversorCHD"))
    }

    @Test
    fun dvdConversionMatchesInputSha1AndVerifies() {
        val iso = makeIso("dvd.iso", 6000)
        val chd = File(dir, "dvd.chd")
        val rec = Recorder()
        val r = convertDvd(iso, chd, rec)
        assertEquals(r.message, ResultCodes.OK, r.code)
        assertEquals(sha1(iso), r.rawSha1)
        assertTrue("progresso não reportado", rec.calls > 0)
        assertTrue("tamanho comprimido não reportado", rec.written > 0)
        assertTrue(chd.length() in 1 until iso.length())
        val v = verify(chd)
        assertEquals(v.message, ResultCodes.OK, v.code)
        assertEquals(r.rawSha1, v.rawSha1)
    }

    @Test
    fun allProfilesAreLosslessAndVerify() {
        val iso = makeIso("perfis.iso", 8000, seed = 3)
        val want = sha1(iso)
        for (profile in Profile.values()) {
            val chd = File(dir, "perfil-${profile.name}.chd")
            val r = convertDvd(iso, chd, null, CompressionParams.of(profile, DiscMode.DVD))
            assertEquals("${profile.name}: ${r.message}", ResultCodes.OK, r.code)
            assertEquals(profile.name, want, r.rawSha1)
            for (threads in listOf(1, 4)) {
                val v = verify(chd, threads)
                assertEquals("${profile.name} verify t=$threads: ${v.message}", ResultCodes.OK, v.code)
                assertEquals(want, v.rawSha1)
            }
        }
    }

    @Test
    fun analyzerRecommendsDvdForPlainIso() {
        val iso = makeIso("dados.iso", 4000)
        val job = Job(1, SourceKind.IMAGE, input(iso), emptyList())
        val a = Analyzer.analyze(ctx, job)
        assertEquals(a.error, null, a.error)
        assertEquals(DiscMode.DVD, a.recommended)
        assertTrue(DiscMode.CD in a.allowedModes)
    }

    @Test
    fun analyzerRejectsInvalidSize() {
        val f = File(dir, "ruim.iso").apply { writeBytes(ByteArray(2048 * 20 + 13)) }
        val a = Analyzer.analyze(ctx, Job(2, SourceKind.IMAGE, input(f), emptyList()))
        assertTrue(a.error != null)
    }

    @Test
    fun cueWithAudioThroughWorkDir() {
        // trilha 1: dados MODE1/2352 (cabeçalho de sincronismo + 2048 + EDC/ECC zerado)
        val data = makeIso("t1src.iso", 400)
        val t1 = File(dir, "Jogo (Track 01).bin")
        data.inputStream().use { input ->
            t1.outputStream().use { out ->
                val user = ByteArray(2048)
                var lba = 0
                while (input.read(user) == 2048) {
                    val a = lba + 150
                    fun bcd(v: Int) = (((v / 10) shl 4) or (v % 10)).toByte()
                    val header = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, bcd(a / 4500), bcd((a / 75) % 60), bcd(a % 75), 1)
                    out.write(header); out.write(user); out.write(ByteArray(288))
                    lba++
                }
            }
        }
        // trilha 2: áudio PCM 16 bits estéreo, 3 segundos
        val t2 = File(dir, "Jogo (Track 02).bin")
        t2.outputStream().use { out ->
            val frames = 3 * 75 * 588
            val buf = ByteArray(frames * 4)
            for (i in 0 until frames) {
                val v = (12000 * sin(2 * Math.PI * 440 * i / 44100)).toInt()
                buf[i * 4] = v.toByte(); buf[i * 4 + 1] = (v shr 8).toByte()
                buf[i * 4 + 2] = (-v).toByte(); buf[i * 4 + 3] = ((-v) shr 8).toByte()
            }
            out.write(buf)
        }
        val cue = File(dir, "Jogo.cue")
        cue.writeText(
            "FILE \"Jogo (Track 01).bin\" BINARY\r\n  TRACK 01 MODE1/2352\r\n    INDEX 01 00:00:00\r\n" +
                "FILE \"Jogo (Track 02).bin\" BINARY\r\n  TRACK 02 AUDIO\r\n    INDEX 00 00:00:00\r\n    INDEX 01 00:02:00\r\n",
        )
        val job = Job(3, SourceKind.CUE, input(cue), listOf(TrackRef(t1.name, input(t1)), TrackRef(t2.name, input(t2))))

        val a = Analyzer.analyze(ctx, job)
        assertEquals(a.error, null, a.error)
        assertEquals(2, a.tracks)

        val chd = File(dir, "jogo.chd")
        val r = WorkDir.prepareCd(ctx, job).use { p ->
            ParcelFileDescriptor.open(chd, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE).use { out ->
                NativeResult.parse(NativeChd.convertCd(p.tocPath, p.trackPaths, p.trackFds, out.fd, 0, 0, "", null))
            }
        }
        assertEquals(r.message, ResultCodes.OK, r.code)
        assertEquals(2, r.tracks)
        assertEquals(ResultCodes.OK, verify(chd).code)
        val info = ParcelFileDescriptor.open(chd, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeChd.chdInfo(it.fd) }
        assertTrue(info, info.contains("\"type\":\"cd\"") && info.contains("\"tracks\":2"))
        // o diretório de trabalho é removido ao fechar
        assertTrue(WorkDir.root(ctx).listFiles().isNullOrEmpty())
    }

    @Test
    fun cancellationStopsAndCanRunAgain() {
        val iso = makeIso("grande.iso", 60000, seed = 7) // ~117 MB
        val chd = File(dir, "cancelado.chd")
        val r = convertDvd(iso, chd, Recorder(cancelAt = 0.2))
        assertEquals(ResultCodes.CANCELLED, r.code)
        val again = convertDvd(makeIso("pequeno.iso", 1000), File(dir, "ok.chd"))
        assertEquals(again.message, ResultCodes.OK, again.code)
    }

    @Test
    fun invalidInputIsRejected() {
        val bad = File(dir, "impar.bin").apply { writeBytes(ByteArray(2048 * 3 + 100)) }
        val r = convertDvd(bad, File(dir, "x.chd"))
        assertEquals(ResultCodes.INVALID_INPUT, r.code)
    }

    @Test
    fun corruptedChdFailsVerification() {
        val iso = makeIso("c.iso", 3000)
        val chd = File(dir, "c.chd")
        assertEquals(ResultCodes.OK, convertDvd(iso, chd).code)
        RandomAccessFile(chd, "rw").use { raf ->
            val pos = chd.length() / 2
            raf.seek(pos)
            val b = ByteArray(64)
            raf.readFully(b)
            for (i in b.indices) b[i] = (b[i].toInt() xor 0x5a).toByte()
            raf.seek(pos)
            raf.write(b)
        }
        assertTrue(verify(chd).code == ResultCodes.VERIFY_FAILED)
    }

    /** Arquivo esparso de ~4,3 GB: valida offsets de 64 bits ponta a ponta. */
    @Test
    fun largerThan4GB() {
        val iso = File(dir, "grande4g.iso")
        val size = (4L * 1024 * 1024 * 1024 + 256L * 1024 * 1024) / 2048 * 2048
        val marker = Random(42).nextBytes(1024 * 1024)
        RandomAccessFile(iso, "rw").use { raf ->
            raf.setLength(size)
            raf.seek(4L * 1024 * 1024 * 1024 - 512 * 1024) // atravessa a fronteira de 4 GiB
            raf.write(marker)
        }
        val chd = File(dir, "grande4g.chd")
        val done = AtomicBoolean(false)
        val r = convertDvd(iso, chd, object : NativeListener {
            override fun onProgress(stage: Int, fraction: Double, ratio: Double, done2: Long, total: Long, written: Long) {
                if (total == size) done.set(true)
            }
            override fun onLog(message: String) {}
        })
        assertEquals(r.message, ResultCodes.OK, r.code)
        assertEquals(size, r.logicalBytes)
        assertTrue(done.get())
        assertEquals(sha1(iso), r.rawSha1)
    }
}
