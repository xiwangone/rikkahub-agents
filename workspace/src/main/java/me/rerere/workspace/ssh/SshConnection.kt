package me.rerere.workspace.ssh

import com.jcraft.jsch.Channel as JschChannel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SocketFactory as JschSocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import me.rerere.common.log.AppLogger
import me.rerere.workspace.StreamCollector
import me.rerere.workspace.StreamWriter
import me.rerere.workspace.WorkspaceCommandResult
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 一条 SSH 连接: 命令走 exec channel, 文件操作复用同一个 SFTP channel。
 *
 * Session 本身允许并发开多个 channel; SFTP channel 不是线程安全的, 由 [sftp] 串行化。
 * 阻塞操作响应线程中断, 调用方可以用中断取消卡在网络上的操作。
 */
@Suppress("TooGenericExceptionCaught")
// JSch 在连接/通道层会抛多种运行时异常，这里统一兜住后再按类型区分重试与上报，
// 只声明具体异常会漏掉未预期的失败形态。
internal class SshConnection private constructor(
    val config: SshConnectionConfig,
    private val session: Session,
    val hostKey: SshHostKey,
    private val channelTimeoutMillis: Int,
    /** 跳板隧道（直连时为 null）；关闭时显式收掉，避免跳板机会话遗留 */
    private val proxy: Proxy? = null,
) : Closeable {
    private val sftpLock = ReentrantLock()
    private var sftpChannel: ChannelSftp? = null
    private val ensuredDirs = ConcurrentHashMap.newKeySet<String>()

    val isConnected: Boolean
        get() = session.isConnected

    // lazy 在初始化抛异常时不会缓存结果, 连接恢复后下次访问会重新获取
    val homeDir: String by lazy { sftp { it.home } }

    val shell: String by lazy { detectShell() }

    fun <T> sftp(block: (ChannelSftp) -> T): T {
        // 可中断地等锁: 前一个操作卡在网络上时, 被取消的等待方能及时返回
        sftpLock.lockInterruptibly()
        try {
            val channel = sftpChannel?.takeIf { it.isConnected } ?: openSftpChannel().also { sftpChannel = it }
            try {
                return block(channel)
            } catch (e: Exception) {
                // 被打断的请求的回应可能稍后才到, 继续用这个 channel 会读到错位的回应, 关掉下次重开
                if (e.isInterruption()) {
                    channel.disconnect()
                    sftpChannel = null
                }
                throw e
            }
        } finally {
            sftpLock.unlock()
        }
    }

    private fun openSftpChannel(): ChannelSftp {
        val channel = openChannel { session.openChannel("sftp") as ChannelSftp }
        connectChannel(step = { openChannel { channel.connect(channelTimeoutMillis) } }, onAbandoned = channel::disconnect)
        return channel
    }

    /**
     * 在后台连接 channel, 被取消时放弃等待: 之后连上了就用 [onAbandoned] 关掉 channel; 失败了则断开整条连接。
     * 失败时 JSch 不会给服务器发 channel close, 远端 channel 可能已经开着, 没被取消时连接池收到异常后会断开连接,
     * 被取消后没有人处理这个异常, 只能在这里断开。
     */
    private fun connectChannel(step: () -> Unit, onAbandoned: () -> Unit) {
        awaitInterruptibly(step, onAbandoned = { onAbandoned() }, onAbandonedFailure = ::close)
    }

    /** 确保远程目录存在, 每条连接对同一目录只检查一次 */
    fun ensureDir(dir: String) {
        if (dir in ensuredDirs) return
        sftp { it.mkdirs(dir) }
        ensuredDirs += dir
    }

    fun exec(command: String, timeoutMillis: Long, stdin: ByteArray? = null): WorkspaceCommandResult {
        val channel = openChannel { session.openChannel("exec") as ChannelExec }
        // Windows 远端不走 POSIX shell：命令经 pwsh 执行，见 wrapForPwsh
        channel.setCommand(if (config.platform == "WINDOWS") wrapForPwsh(command) else command)
        // 输入流必须在 connect 之前取, 否则开头的输出会丢
        val stdoutStream = channel.inputStream
        val stderrStream = channel.extInputStream
        channel.connectExec()
        val stdout = StreamCollector(stdoutStream)
        val stderr = StreamCollector(stderrStream)
        // 没有 stdin 时立即发送 EOF, 避免按 isatty 判断的工具一直等待输入
        val stdinWriter = if (stdin != null) {
            StreamWriter(channel.outputStream, stdin)
        } else {
            runCatching { channel.outputStream.close() }
            null
        }
        try {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (!channel.isClosed && System.nanoTime() < deadline) {
                Thread.sleep(EXEC_POLL_INTERVAL_MS)
            }
            val finished = channel.isClosed
            if (!finished) {
                channel.kill()
            }
            stdinWriter?.join(1_000)
            stdout.join(1_000)
            stderr.join(1_000)
            // channel 已关闭却没收到 exit status：与超时（timedOut=true）的 -1 不同，
            // 在 stderr 留痕以便调用方区分“命令没给退出码”和“启动失败”
            val exitStatus = if (finished) channel.exitStatus else -1
            val stderrText = if (finished && exitStatus < 0) {
                stderr.text().trimEnd() + "\nSSH channel closed without an exit status"
            } else {
                stderr.text()
            }
            return WorkspaceCommandResult(
                exitCode = exitStatus,
                stdout = stdout.text(),
                stderr = stderrText,
                timedOut = !finished,
                truncated = stdout.truncated || stderr.truncated,
            )
        } catch (e: InterruptedException) {
            // 协程取消时 runInterruptible 会中断这里的等待, 关掉 channel 避免命令继续执行
            channel.kill()
            stdinWriter?.join(1_000)
            stdout.join(1_000)
            stderr.join(1_000)
            throw e
        } finally {
            channel.disconnect()
        }
    }

    /**
     * 在带 PTY 的 exec channel 里运行 [command], [onClose] 在 shell 关闭时调用
     *
     * 交互终端走这里：PTY 会把 stderr 合并进 stdout，终端按原始字节流渲染，
     * 登录 shell 的欢迎信息是给人看的，不污染机器解析。非交互的 executeCommand
     * 不走 PTY（见 SshWorkspaceBackend.executeCommand 的 -l 说明）。
     */
    fun openShell(command: String, columns: Int, rows: Int, onClose: () -> Unit): SshShell {
        val channel = openChannel { session.openChannel("exec") as ChannelExec }
        channel.setCommand(command)
        channel.setPty(true)
        channel.setPtyType(TERMINAL_TYPE, columns, rows, 0, 0)
        channel.setTerminalMode(TERMINAL_MODES)
        // 输入流必须在 connect 之前取, 否则开头的输出会丢
        val input = channel.inputStream
        val output = channel.outputStream
        // 连接失败时 JSch 自己会清理; 被取消时后台可能还在打开, 不能提前断开, 交给 connectExec 的放弃处理
        channel.connectExec()
        return SshShell(channel, input, output, this, onClose)
    }

    /**
     * Windows 远端：脚本经 pwsh -EncodedCommand 执行。脚本以 UTF-16LE Base64 编码传递，
     * 免去远端 shell 的引号转义；执行前先把控制台编码切到 UTF-8，避免中文输出乱码。
     */
    private fun wrapForPwsh(command: String): String {
        val script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; \$OutputEncoding=[Text.Encoding]::UTF8; $command"
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return "pwsh -NoProfile -NonInteractive -EncodedCommand $encoded"
    }

    // 不等 SFTP 锁: 断开 session 会关闭所有 channel, 卡住的 SFTP 操作随之失败并释放锁
    override fun close() {
        session.disconnect()
        // 目标会话断开时跳板隧道不一定随之关闭，这里显式收掉，避免跳板机会话遗留
        runCatching { proxy?.close() }
    }

    private inline fun <T> openChannel(block: () -> T): T = try {
        block()
    } catch (e: JSchException) {
        throw SshChannelOpenException(e)
    }

    /**
     * 连接 exec channel。JSch 的 connect 先打开 channel, 再发送 exec 请求并等待服务器回应:
     * 只有打开阶段的失败能确定命令还没发出去, 按 [SshChannelOpenException] 允许重连重试;
     * 等待回应超时等其余失败时命令可能已经在执行, 原样抛出, 不可重复的命令不会再执行一遍。
     *
     * 后一种情况下 JSch 不会给服务器发 channel close, 远端的 channel 和进程会一直留到连接断开,
     * 而单独关闭这个 channel 的方法不对外开放, 所以断开整条连接, 连接上的其他操作也会随之失败。
     *
     * 被取消时的处理见 [connectChannel]。
     */
    private fun ChannelExec.connectExec() {
        connectChannel(
            step = {
                try {
                    connect(channelTimeoutMillis)
                } catch (e: JSchException) {
                    // JSch 没有区分这两个阶段的接口, 只能按打开阶段的异常信息判断；
                    // 用子串匹配而不用整句相等，JSch 文案微调时仍能认出
                    if (CHANNEL_OPEN_FAILURE_MESSAGES.any { e.message?.contains(it) == true }) throw SshChannelOpenException(e)
                    this@SshConnection.close()
                    throw e
                }
            },
            onAbandoned = { kill() },
        )
    }

    // 不少 sshd 不处理 signal 请求, 断开 channel 后远程进程会在下次读写管道时退出
    private fun ChannelExec.kill() {
        runCatching { sendSignal("KILL") }
        disconnect()
    }

    private fun detectShell(): String {
        val result = exec("command -v bash", SHELL_DETECT_TIMEOUT_MS)
        // 用户的 rc 文件可能会往 stdout 打印内容, 只认最后一个绝对路径
        val bash = result.stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("/") }
        return if (result.exitCode == 0 && bash != null) bash else "/bin/sh"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_CHANNEL_TIMEOUT_MS = 15_000
        private const val KEEPALIVE_INTERVAL_MS = 15_000
        private const val KEEPALIVE_COUNT_MAX = 3
        private const val EXEC_POLL_INTERVAL_MS = 20L
        private const val SHELL_DETECT_TIMEOUT_MS = 5_000L
        private const val TERMINAL_TYPE = "xterm-256color"
        private const val TAG = "SshConnection"

        // RFC 4254 的 PTY 模式编码: 开启 IUTF8 (RFC 8160 opcode 42), 让行编辑按 UTF-8 字符退格; 以 TTY_OP_END 结尾
        private val TERMINAL_MODES = byteArrayOf(42, 0, 0, 0, 1, 0)

        // Channel.sendChannelOpen 失败时的异常信息片段, 此时 exec 请求还没发出
        private val CHANNEL_OPEN_FAILURE_MESSAGES = setOf("session is down", "channel is not opened")

        fun open(config: SshConnectionConfig, channelTimeoutMillis: Int = DEFAULT_CHANNEL_TIMEOUT_MS): SshConnection {
            val target = SshEndpoint(
                host = config.host,
                port = config.port,
                username = config.username,
                credentials = SshCredentials(config.authType, config.password, config.privateKey, config.passphrase),
                hostKey = config.hostKey,
                jumpHost = false,
            )
            val jump = config.jumpHost?.let {
                SshEndpoint(
                    host = it.host,
                    port = it.port,
                    username = it.username,
                    credentials = SshCredentials(it.authType, it.password, it.privateKey, it.passphrase),
                    hostKey = it.hostKey,
                    jumpHost = true,
                )
            }
            // 两边的凭据都先检查，配置有误时不必先连上跳板机
            val targetJsch = target.newJSch()
            val jumpJsch = jump?.let { it to it.newJSch() }
            // 隧道的读超时同时充当握手超时（直连时是 CONNECT_TIMEOUT_MS）和 keepalive 间隔，两者一致
            val proxy = jumpJsch?.let { (endpoint, jsch) ->
                JumpHostProxy(connectSession(endpoint, jsch, proxy = null).session, KEEPALIVE_INTERVAL_MS.toLong())
            }
            val connected = try {
                connectSession(target, targetJsch, proxy)
            } catch (e: Exception) {
                // 目标主机连接失败时 JSch 会关掉 proxy，这里兜底，保证跳板机的连接不会遗留
                proxy?.close()
                throw e
            }
            return SshConnection(config, connected.session, connected.hostKey, channelTimeoutMillis, proxy)
        }

        private fun SshEndpoint.newJSch(): JSch {
            val jsch = JSch()
            when (credentials.authType) {
                SshAuthType.PASSWORD -> {
                    if (credentials.password.isNullOrEmpty()) throw IOException("SSH password is required for $label")
                }

                SshAuthType.PRIVATE_KEY -> {
                    val privateKey = credentials.privateKey?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IOException("SSH private key is required for $label")
                    try {
                        jsch.addIdentity(
                            "rikkahub",
                            "$privateKey\n".toByteArray(),
                            null,
                            credentials.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(),
                        )
                    } catch (e: JSchException) {
                        throw IOException("Invalid SSH private key or passphrase for $label: ${e.message}", e)
                    }
                }
            }
            return jsch
        }

        /** 建立 SSH 会话并完成主机密钥校验和认证；[proxy] 不为空时经它连接 */
        private fun connectSession(endpoint: SshEndpoint, jsch: JSch, proxy: Proxy?): ConnectedSession {
            val hostKeys = TrustedHostKeyRepository(endpoint.hostKey, endpoint.jumpHost)
            val passwordUserInfo =
                if (endpoint.credentials.authType == SshAuthType.PASSWORD) PasswordUserInfo(endpoint.credentials.password!!) else null
            // 内嵌隧道：直连时目标若被隧道接管（如 tailnet 网段），改连 App 内代理端口；经跳板时目标由隧道直达，不改
            val (dialHost, dialPort) =
                if (proxy == null) TunnelRouter.resolve(endpoint.host, endpoint.port)
                else endpoint.host to endpoint.port
            val session = jsch.getSession(endpoint.username, dialHost, dialPort).apply {
                setHostKeyRepository(hostKeys)
                setConfig("StrictHostKeyChecking", "yes")
                when (endpoint.credentials.authType) {
                    SshAuthType.PASSWORD -> {
                        setConfig("PreferredAuthentications", "password,keyboard-interactive")
                        setPassword(endpoint.credentials.password)
                        passwordUserInfo?.let { userInfo = it }
                    }

                    SshAuthType.PRIVATE_KEY -> setConfig("PreferredAuthentications", "publickey")
                }
                setServerAliveInterval(KEEPALIVE_INTERVAL_MS)
                serverAliveCountMax = KEEPALIVE_COUNT_MAX
                if (proxy != null) setProxy(proxy)
            }
            try {
                session.connect(CONNECT_TIMEOUT_MS)
            } catch (e: JSchException) {
                // 建连失败记一条 e：label 只含 user@host:port，不含凭据；主机密钥拒绝 /
                // 额外验证 / 其他失败三个分支都走这里，详情见抛出的异常。直连与跳板机
                // 各调一次 connectSession，失败方各自记一条。
                AppLogger.e(TAG, "connectSession failed: ${endpoint.label}", e)
                hostKeys.rejection?.let { throw it }
                if (passwordUserInfo?.sawExtraVerificationPrompt == true) {
                    throw IOException("SSH authentication failed: server requested additional verification (for example a one-time code)", e)
                }
                throw IOException("SSH connection to ${endpoint.label} failed: ${e.message}", e)
            }
            val hostKey = hostKeys.presented ?: run {
                session.disconnect()
                throw IOException("SSH server did not present a host key")
            }
            return ConnectedSession(session, hostKey)
        }

        /** 用一条临时连接验证配置, 并取回远程环境的基本信息 */
        fun probe(config: SshConnectionConfig, includeHostProbe: Boolean = true): SshProbeResult {
            // 被取消时放弃等待, 之后建好的连接直接关掉
            val connection = awaitInterruptibly({ open(config) }, onAbandoned = { it.close() })
            return connection.use {
                val remoteDir = config.remoteDir.trim()
                // Relative paths resolve against the login home directory; a blank
                // directory means the home directory itself.
                val resolvedDir = when {
                    remoteDir.isBlank() -> connection.homeDir
                    remoteDir.startsWith("/") -> remoteDir
                    else -> "${connection.homeDir.trimEnd('/')}/$remoteDir"
                }
                SshProbeResult(
                    hostKey = connection.hostKey,
                    homeDir = connection.homeDir,
                    shell = connection.shell,
                    remoteDirExists = connection.sftp { sftp ->
                        sftp.statOrNull(resolvedDir)?.isDir == true
                    },
                    // 首次连接主机探针：与 app SSH 共用 SshHostProbe（同一份命令/解析/摘要）；
                    // exec 在 config.platform == "WINDOWS" 时自动包 pwsh，故 execWrapsWindows
                    // 直接取该条件。探针失败只记 null，不影响本次测试连接。
                    // 设置为关闭时（includeHostProbe=false）不跑探针，hostProbe 保持 null。
                    hostProbe = if (includeHostProbe) {
                        SshHostProbe.runProbe(
                            exec = { script, timeout -> connection.exec(script, timeout) },
                            platform = config.platform,
                            execWrapsWindows = config.platform == "WINDOWS",
                        )
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 打开 channel 失败, 请求还没有发到服务器, 重连后重试是安全的 */
internal class SshChannelOpenException(cause: Throwable) : IOException(cause.message, cause)

/** 一台要连接的 SSH 服务器：目标主机或跳板机 */
private class SshEndpoint(
    val host: String,
    val port: Int,
    val username: String,
    val credentials: SshCredentials,
    val hostKey: SshHostKey?,
    val jumpHost: Boolean,
) {
    val label: String
        get() = if (jumpHost) "jump host $username@$host:$port" else "$username@$host:$port"
}

private class ConnectedSession(val session: Session, val hostKey: SshHostKey)

/**
 * 经跳板机的 direct-tcpip channel 连接目标主机，目标会话断开时连同跳板机的会话一起断开；
 * 跳板机断开时 channel 随之关闭，目标会话读到 EOF 也会断开。
 *
 * 隧道没有 socket，JSch 设不了读超时：握手时目标主机不回应会一直等下去，连上之后也没有触发 keepalive 的超时，
 * 跳板机还在而目标主机失联时发现不了。所以接收数据的管道自带 [readTimeoutMillis] 读超时，
 * 抛出的 [SocketTimeoutException] 在 JSch 看来和 socket 读超时一样：握手时连接失败，连上之后发送 keepalive。
 */
private class JumpHostProxy(private val jumpSession: Session, readTimeoutMillis: Long) : Proxy {
    private val input = TunnelInputStream(readTimeoutMillis)

    @Volatile
    private var channel: JschChannel? = null

    @Volatile
    private var output: OutputStream? = null

    override fun connect(socketFactory: JschSocketFactory?, host: String, port: Int, timeout: Int) {
        val channel = jumpSession.getStreamForwarder(host, port)
        this.channel = channel
        // 收到的数据写进管道；必须在 connect 之前设置，否则开头的数据会丢
        channel.setOutputStream(input.sink)
        try {
            channel.connect(timeout)
        } catch (e: JSchException) {
            // 常见原因：跳板机连不到目标主机，或者 sshd 设置了 AllowTcpForwarding no
            throw JSchException("jump host could not open a tunnel to $host:$port (${e.message})", e)
        }
        output = channel.outputStream
    }

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output ?: throw IOException("Tunnel is not connected")

    override fun getSocket(): Socket? = null

    override fun close() {
        channel?.disconnect()
        jumpSession.disconnect()
        input.close()
    }
}

/**
 * 隧道收到的数据：跳板机会话的读线程写入 [sink]，目标会话从这里读取。
 *
 * 不限容量：写入方阻塞会卡住跳板机会话的读线程，窗口调整也收不到，和目标会话互相等待；
 * 堆积的数据受目标会话各 channel 的窗口限制，不会无限增长。
 * 和 socket 一样不响应线程中断，断开时靠 [close] 唤醒读取方。
 */
private class TunnelInputStream(private val readTimeoutMillis: Long) : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray = EMPTY
    private var position = 0

    @Volatile
    private var closed = false

    val sink: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Tunnel closed")
            if (len > 0) chunks.put(b.copyOfRange(off, off + len))
        }

        override fun close() = this@TunnelInputStream.close()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position == current.size) {
            current = nextChunk() ?: return -1
            position = 0
        }
        val count = minOf(len, current.size - position)
        System.arraycopy(current, position, b, off, count)
        position += count
        return count
    }

    override fun available(): Int = current.size - position

    override fun close() {
        closed = true
        chunks.offer(END)
    }

    private fun nextChunk(): ByteArray? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis)
        var interrupted = false
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw SocketTimeoutException("Read timed out")
                val chunk = try {
                    chunks.poll(remaining, TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    interrupted = true
                    continue
                } ?: continue
                if (chunk === END) {
                    // 之后的读取同样返回 EOF
                    chunks.offer(END)
                    return null
                }
                return chunk
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
        val END = ByteArray(0)
    }
}

// JSch 等服务器回应时会吞掉线程中断, 这类步骤放到这里的线程上执行, 见 awaitInterruptibly
// 有界线程池：JSch 阻塞步骤（建连/开 channel/等回应）各占一个线程，
// 无界缓存池在重连风暴时会随请求数无限长线程
private val blockingSteps: ExecutorService =
    (Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "ssh-blocking-step").apply { isDaemon = true }
    } as java.util.concurrent.ThreadPoolExecutor).apply {
        setKeepAliveTime(60L, java.util.concurrent.TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

/**
 * 在后台线程执行 [step], 当前线程可中断地等待结果。
 *
 * JSch 建立连接、打开 channel 和等待请求回应时会吞掉线程中断, 直接调用的话, 取消要等到服务器回应或超时,
 * 中断标记也会丢失。等待被中断时立即抛出 [InterruptedException], 之后 [step] 成功得到的结果交给 [onAbandoned] 清理,
 * 失败则调用 [onAbandonedFailure]。
 */
internal fun <T> awaitInterruptibly(
    step: () -> T,
    onAbandoned: (T) -> Unit,
    onAbandonedFailure: () -> Unit = {},
): T {
    if (Thread.interrupted()) throw InterruptedException()
    val future = CompletableFuture.supplyAsync({ step() }, blockingSteps)
    try {
        return future.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: InterruptedException) {
        future.whenComplete { result, error -> if (error == null) onAbandoned(result) else onAbandonedFailure() }
        throw e
    }
}

/** 是否因线程中断而失败: JSch 会把读管道时的 InterruptedIOException 包进 SftpException 等异常里 */
internal fun Throwable.isInterruption(): Boolean = generateSequence(this) { it.cause }.any {
    it is InterruptedException || it is InterruptedIOException && it !is SocketTimeoutException
}

/** 逐级创建目录, 已存在的跳过 */
internal fun ChannelSftp.mkdirs(dir: String) {
    var current = ""
    dir.split('/').filter { it.isNotEmpty() }.forEach { segment ->
        current += "/$segment"
        val attrs = statOrNull(current)
        if (attrs == null) {
            // mkdir 不做通配符展开, 传原始路径
            mkdir(current)
        } else if (!attrs.isDir) {
            throw IOException("Not a directory: $current")
        }
    }
}

internal fun ChannelSftp.statOrNull(path: String) = try {
    stat(path.sftpQuote())
} catch (e: SftpException) {
    if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
}

/** JSch 的 stat/ls/get/put/rm/rmdir 会把路径里的 * ? 当通配符展开, 反斜杠转义后按字面处理 */
internal fun String.sftpQuote(): String =
    replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[").replace("]", "\\]")

/**
 * 只认用户确认过的那一把主机公钥。校验发生在认证之前,
 * 未知或不一致时连接直接失败, 密码不会发给未经确认的服务器。
 */
private class TrustedHostKeyRepository(
    private val trusted: SshHostKey?,
    private val jumpHost: Boolean = false,
) : HostKeyRepository {
    @Volatile
    var presented: SshHostKey? = null

    @Volatile
    var rejection: IOException? = null

    override fun check(host: String, key: ByteArray): Int {
        val type = runCatching { HostKey(host, key).type }.getOrDefault("unknown")
        val actual = SshHostKey(type = type, key = Base64.getEncoder().encodeToString(key))
        presented = actual
        return when {
            trusted == null -> {
                rejection = SshUnknownHostKeyException(actual, jumpHost)
                HostKeyRepository.NOT_INCLUDED
            }

            trusted.key == actual.key -> {
                rejection = null
                HostKeyRepository.OK
            }

            else -> {
                rejection = SshHostKeyChangedException(trusted, actual, jumpHost)
                HostKeyRepository.CHANGED
            }
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit

    override fun remove(host: String, type: String) = Unit

    override fun remove(host: String, type: String, key: ByteArray) = Unit

    override fun getKnownHostsRepositoryID(): String = "rikkahub"

    override fun getHostKey(): Array<HostKey> = trustedHostKeys(host = "", type = null)

    // JSch 会把这里返回的算法排到协商列表最前, 保证服务器出示的是已信任的那一类密钥
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = trustedHostKeys(host.orEmpty(), type)

    private fun trustedHostKeys(host: String, type: String?): Array<HostKey> {
        val hostKey = trusted?.let {
            runCatching { HostKey(host, Base64.getDecoder().decode(it.key)) }.getOrNull()
        } ?: return emptyArray()
        return if (type == null || hostKey.type == type) arrayOf(hostKey) else emptyArray()
    }
}

/** 密码认证, 同时应答 keyboard-interactive 的密码提示 */
private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
    @Volatile
    var sawExtraVerificationPrompt: Boolean = false
        private set

    override fun getPassphrase(): String? = null

    override fun getPassword(): String = password

    override fun promptPassword(message: String?): Boolean = true

    override fun promptPassphrase(message: String?): Boolean = false

    override fun promptYesNo(message: String?): Boolean = false

    override fun showMessage(message: String?) = Unit

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String> {
        val prompts = prompt ?: return emptyArray()
        return Array(prompts.size) { index ->
            val text = prompts[index]
            if (isPasswordPrompt(text, echo?.getOrNull(index) == true)) {
                password
            } else {
                // Non-password prompts (one-time code, token, captcha) are not
                // answered with the password; answering them would only burn the
                // attempt and hide the real requirement from the user.
                sawExtraVerificationPrompt = true
                ""
            }
        }
    }

    /**
     * Only prompts that look like a password prompt get the password: echoed
     * prompts are shown to the user (username, code), and prompts mentioning a
     * one-time code or token are an extra verification step, not the password.
     */
    private fun isPasswordPrompt(prompt: String, echo: Boolean): Boolean {
        if (echo) return false
        val lower = prompt.lowercase()
        if (!lower.contains("password")) return false
        return EXTRA_VERIFICATION_WORDS.none { it in lower }
    }

    companion object {
        private val EXTRA_VERIFICATION_WORDS = listOf(
            "one-time",
            "one time",
            "otp",
            "verification",
            "code",
            "token",
            "2fa",
            "two-factor",
            "authenticator",
        )
    }
}
