package me.rerere.rikkahub.data.ai.tools.local

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory as JschSocketFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Split a proxy command into an argv list, expanding `%h` (target host) and
 * `%p` (target port) before splitting, the same way OpenSSH substitutes them.
 * Single quotes are literal, double quotes allow `\"` and `\\` escapes, and
 * an unquoted backslash escapes the next character. Throws
 * [IllegalArgumentException] on an empty command or an unbalanced quote.
 */
internal fun splitProxyCommand(command: String, host: String, port: Int): List<String> {
    val text = command.replace("%h", host).replace("%p", port.toString())
    return ProxyCommandScanner(text).scan()
}

/**
 * Tokenizer for [splitProxyCommand]: tracks quote state, the current token
 * and the argv built so far. One method per quote state keeps each branch
 * flat instead of nesting the whole state machine in a single function.
 */
private class ProxyCommandScanner(private val text: String) {
    private val argv = mutableListOf<String>()
    private val current = StringBuilder()
    private var inSingle = false
    private var inDouble = false
    private var hasToken = false
    private var i = 0

    fun scan(): List<String> {
        while (i < text.length) {
            step(text[i])
            i++
        }
        if (inSingle || inDouble) {
            throw IllegalArgumentException("proxy command has an unbalanced quote")
        }
        flushToken()
        if (argv.isEmpty()) {
            throw IllegalArgumentException("proxy command is empty")
        }
        return argv
    }

    private fun step(c: Char) {
        when {
            inSingle -> stepSingle(c)
            inDouble -> stepDouble(c)
            c == '\'' -> {
                inSingle = true
                hasToken = true
            }
            c == '"' -> {
                inDouble = true
                hasToken = true
            }
            c == '\\' -> stepEscape()
            c.isWhitespace() -> flushToken()
            else -> {
                current.append(c)
                hasToken = true
            }
        }
    }

    private fun stepSingle(c: Char) {
        if (c == '\'') inSingle = false else current.append(c)
    }

    private fun stepDouble(c: Char) {
        when (c) {
            '"' -> inDouble = false
            '\\' -> stepDoubleEscape()
            else -> current.append(c)
        }
    }

    private fun stepDoubleEscape() {
        val next = text.getOrNull(i + 1)
        if (next == '"' || next == '\\') {
            current.append(next)
            i++
        } else {
            current.append('\\')
        }
    }

    private fun stepEscape() {
        val next = text.getOrNull(i + 1)
        if (next != null) {
            current.append(next)
            i++
        }
        hasToken = true
    }

    private fun flushToken() {
        if (hasToken) {
            argv.add(current.toString())
            current.clear()
            hasToken = false
        }
    }
}

/**
 * JSch [Proxy] that runs a local command (argv from [splitProxyCommand]) and
 * uses its stdin/stdout as the transport, the same role ProxyCommand plays in
 * OpenSSH. The process owns routing, so the caller must not resolve the
 * target host or open a tunnel first. Standard error goes to /dev/null: the
 * stream must stay pure SSH bytes, and an undrained stderr pipe could stall
 * the process.
 */
internal class ProcessProxyCommand(
    private val argv: List<String>,
) : Proxy {
    private var process: Process? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun connect(socketFactory: JschSocketFactory?, host: String, port: Int, timeout: Int) {
        if (process != null) return
        val proc = try {
            ProcessBuilder(argv)
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                .start()
        } catch (e: IOException) {
            throw JSchException("proxy command failed to start: ${e.message}", e)
        }
        process = proc
        input = proc.inputStream
        output = proc.outputStream
    }

    override fun getInputStream(): InputStream =
        input ?: error("proxy command not connected")

    override fun getOutputStream(): OutputStream =
        output ?: error("proxy command not connected")

    override fun getSocket(): Socket? = null

    override fun close() {
        runCatching { process?.destroy() }
        runCatching { input?.close() }
        runCatching { output?.close() }
        process = null
        input = null
        output = null
    }
}
