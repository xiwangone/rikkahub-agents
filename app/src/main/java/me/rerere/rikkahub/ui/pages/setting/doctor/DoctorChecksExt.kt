package me.rerere.rikkahub.ui.pages.setting.doctor

import android.os.Build
import android.os.SystemClock
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.subagent.SubAgentModelResolver
import me.rerere.rikkahub.subagent.SubAgentProfile
import java.io.File
import java.net.InetAddress


    // ----- Network & providers ---------------------------------------------------------

internal suspend fun DoctorChecks.networkChecks(): List<DoctorCheck> =
        buildList {
            runCatching {
                val settings = settingsStore.settingsFlow.first()
                val provs = settings.providers
                val configured =
                    provs.count { p ->
                        when (p) {
                            is me.rerere.ai.provider.ProviderSetting.OpenAI -> p.apiKey.isNotBlank()

                            is me.rerere.ai.provider.ProviderSetting.Google -> p.apiKey.isNotBlank()

                            is me.rerere.ai.provider.ProviderSetting.Claude -> p.apiKey.isNotBlank()

                            is me.rerere.ai.provider.ProviderSetting.AICore -> p.enabled

                            // on-device, no API key
                            // Local provider (LiteRT): usable when enabled AND at least one model has
                            // been loaded/downloaded. A disabled provider with no models is the factory
                            // default — don't count it.
                            is me.rerere.ai.provider.ProviderSetting.LiteRtLocal -> p.enabled && p.models.isNotEmpty()

                            is me.rerere.ai.provider.ProviderSetting.Codex -> p.enabled

                            // OAuth, no API key
                            is me.rerere.ai.provider.ProviderSetting.Grok -> p.enabled // OAuth, no API key

                            // Basic Auth / Bearer token, no API key
                            is me.rerere.ai.provider.ProviderSetting.Backend -> p.enabled

                            is me.rerere.ai.provider.ProviderSetting.GeminiOAuth -> p.enabled

                            // on-device, no API key
                            is me.rerere.ai.provider.ProviderSetting.LlamaCppLocal -> p.enabled && p.models.isNotEmpty()
                        }
                    }
                add(
                    DoctorCheck(
                        id = "net.providers",
                        category = DoctorCategory.Network,
                        labelRes = R.string.doctor_net_01,
                        detail = str(R.string.doctor_msg_providers, configured, provs.size),
                        severity = if (configured > 0) Severity.OK else Severity.WARN,
                        fix = FixAction.OpenAppRoute(R.string.doctor_net_02, AppRouteKey.SettingProvider),
                    ),
                )
            }
            // LiteRT accelerator status. The runtime's GPU -> CPU fallback is silent today:
            // if the device's OpenCL/OpenGL delegate fails to init (e.g. MLDrift's
            // "CreateSharedMemoryManager not-implemented" error on some Adreno drivers), the
            addAll(buildLiteRtGpuChecks())
            addAll(buildDnsSanityCheck())
        }

private suspend fun DoctorChecks.buildLiteRtGpuChecks(): List<DoctorCheck> = buildList {
            // model loads on CPU and the user has no UI indication. LiteRtProvider now
            // persists the actually-chosen accelerator after every load; surface that here
            // so the user can confirm GPU is engaged.
            runCatching {
                val prefs = localRuntimePreferences
                if (prefs != null) {
                    val accel = prefs.acceleratorFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                    val forceCpu = prefs.forceCpu(me.rerere.locallm.LocalRuntime.LiteRT)
                    val detail =
                        when {
                            accel == null -> {
                                str(R.string.doctor_msg_litert_unprobed)
                            }

                            forceCpu && accel == "CPU" -> {
                                str(R.string.doctor_msg_litert_cpu_disabled) +
                                    str(R.string.doctor_msg_litert_enable_hint)
                            }

                            accel == "CPU" -> {
                                str(R.string.doctor_msg_litert_cpu_mldrift)
                            }

                            accel == "GPU" -> {
                                str(R.string.doctor_msg_litert_gpu)
                            }

                            accel == "QNN" || accel == "NPU" -> {
                                "NPU（Qualcomm QNN delegate）。"
                            }

                            accel == "NNAPI" -> {
                                "NNAPI。"
                            }

                            else -> {
                                str(R.string.doctor_msg_litert_backend, accel)
                            }
                        }
                    val severity =
                        when {
                            accel == null -> Severity.INFO

                            accel == "CPU" && !forceCpu -> Severity.WARN

                            // unexpected fallback
                            else -> Severity.OK
                        }
                    add(
                        DoctorCheck(
                            id = "net.litert_accel",
                            category = DoctorCategory.Network,
                            labelRes = R.string.doctor_net_10,
                            detail = detail,
                            severity = severity,
                            fix =
                                FixAction.OpenAppRoute(
                                    R.string.doctor_net_11,
                                    AppRouteKey.SettingProvider,
                                ),
                        ),
                    )
                    // Performance telemetry — surface the last-known prefill/decode tok/s for
                    // each model so the user (and the support team triaging a slow report)
                    // can see at a glance whether the runtime is hitting expected rates. We
                    // INFO when present; WARN never (the model could legitimately be slow on a
                    // weak device — the user knows their hardware better than we do).
                    val perfMap = prefs.perfTelemetryFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                    if (perfMap.isNotEmpty()) {
                        val rows = perfMap.values.sortedByDescending { it.sampledAtMs }
                        val detail =
                            rows.joinToString("\n") { s ->
                                val spec = if (s.specDecodingEngaged) ", MTP on" else ""
                                "${s.modelId}: prefill ${"%.1f".format(s.prefillTps)} tok/s, " +
                                    "decode ${"%.1f".format(s.decodeTps)} tok/s$spec"
                            }
                        add(
                            DoctorCheck(
                                id = "net.litert_perf",
                                category = DoctorCategory.Network,
                                labelRes = R.string.doctor_net_12,
                                detail =
                                    str(R.string.doctor_msg_rate_detail, detail),
                                severity = Severity.INFO,
                                fix =
                                    FixAction.OpenAppRoute(
                                        R.string.doctor_net_11,
                                        AppRouteKey.SettingProvider,
                                    ),
                            ),
                        )
                    }
                    // Vision-encoder availability — surface any models the runtime had to drop
                    // to text-only on this device's GPU. The provider's vision-CPU fallback
                    // means a multimodal model still works for chat, but the user has lost
                    // image input on this chip. Most common cause: Adreno 7xx + restrictive
                    // OEM linker namespace (One UI / OriginOS) hitting LiteRT-LM
                    // issue #2292 (gpu_backend_opengl.cc:CreateSharedMemoryManager UNIMPLEMENTED).
                    val visionUnavailable =
                        prefs
                            .visionUnavailableFlow(me.rerere.locallm.LocalRuntime.LiteRT)
                            .first()
                    if (visionUnavailable.isNotEmpty()) {
                        add(
                            DoctorCheck(
                                id = "net.litert_vision",
                                category = DoctorCategory.Network,
                                labelRes = R.string.doctor_net_14,
                                detail =
                                    str(
                                        R.string.doctor_msg_vision_detail,
                                        visionUnavailable.joinToString(", "),
                                    ),
                                severity = Severity.WARN,
                                fix =
                                    FixAction.OpenAppRoute(
                                        R.string.doctor_net_11,
                                        AppRouteKey.SettingProvider,
                                    ),
                            ),
                        )
                    }
                }
            }
        }

private suspend fun DoctorChecks.buildDnsSanityCheck(): List<DoctorCheck> = buildList {
            // DNS sanity — real lookup timing + per-server probe of the active
            // network's DNS servers (the system resolver's actual DNS server).
            // The primary target keeps the check meaningful on any working network; the
            // secondary target surfaces resolver-level filtering as info.
            val primaryTarget = "tencent.com"
            val secondaryTarget = "google.com"
            val lookupStart = SystemClock.elapsedRealtime()
            val resolvedIp =
                withTimeoutOrNull(2_500L) {
                    runCatching { InetAddress.getByName(primaryTarget).hostAddress }.getOrNull()
                }
            val lookupMs = SystemClock.elapsedRealtime() - lookupStart
            val secondaryIp =
                withTimeoutOrNull(2_500L) {
                    runCatching { InetAddress.getByName(secondaryTarget).hostAddress }.getOrNull()
                }
            val dnsServers = (DnsProbe.activeServers(context) + DnsProbe.PUBLIC_SERVERS).distinct()
            val probes =
                if (dnsServers.isEmpty()) {
                    emptyList()
                } else {
                    withTimeoutOrNull(10_000L) { DnsProbe.probeAll(dnsServers, primaryTarget) } ?: emptyList()
                }
            val dnsOk = resolvedIp != null
            // Show only the fastest reachable resolver; keep one failure if none.
            val probeSummary =
                probes.filter { it.ok }.minByOrNull { it.rttMs }?.let {
                    str(R.string.doctor_dns_probe_ok, it.server, it.rttMs)
                } ?: probes.firstOrNull()?.let {
                    str(R.string.doctor_dns_probe_fail, it.server)
                }
            val dnsDetail =
                if (dnsOk) {
                    buildString {
                        append(str(R.string.doctor_msg_dns_ok, primaryTarget, lookupMs))
                        append('\n')
                        append(secondaryTarget)
                        append(" -> ")
                        if (secondaryIp != null) {
                            append(secondaryIp)
                            append(" (secondary ok)")
                        } else {
                            append("timeout (secondary blocked?)")
                        }
                        if (!probeSummary.isNullOrEmpty()) {
                            append('\n')
                            append(str(R.string.doctor_dns_servers, probeSummary))
                        }
                    }
                } else {
                    str(R.string.doctor_msg_dns_failed)
                }
            add(
                DoctorCheck(
                    id = "net.dns",
                    category = DoctorCategory.Network,
                    labelRes = R.string.doctor_net_16,
                    detail = dnsDetail,
                    severity = if (dnsOk) Severity.OK else Severity.WARN,
                ),
            )
        }

    // ----- Termux ----------------------------------------------------------------------

internal fun DoctorChecks.termuxChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> =
        buildList {
            val needers = requirersOf(Capability.Termux, enabled)
            // Skip the entire category when no Termux-using tool is enabled — keeps the
            // Doctor screen focused on what the user actually configured.
            if (needers.isEmpty()) return@buildList

            val pm = context.packageManager
            val termuxInstalled =
                runCatching {
                    pm.getPackageInfo("com.termux", 0)
                    true
                }.getOrDefault(false)
            add(
                DoctorCheck(
                    id = "termux.installed",
                    category = DoctorCategory.Termux,
                    labelRes = R.string.doctor_termux_01,
                    detail =
                        if (termuxInstalled) {
                            str(R.string.doctor_msg_termux_installed)
                        } else {
                            str(R.string.doctor_msg_termux_missing, needers.joinToString(", ") { it.shortName(context) })
                        },
                    severity = if (termuxInstalled) Severity.OK else Severity.WARN,
                ),
            )
            if (termuxInstalled) {
                val runCommandPerm =
                    runCatching {
                        val perm = "com.termux.permission.RUN_COMMAND"
                        context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }.getOrDefault(false)
                add(
                    DoctorCheck(
                        id = "termux.run_command",
                        category = DoctorCategory.Termux,
                        labelRes = R.string.doctor_termux_03,
                        detail =
                            if (runCommandPerm) {
                                str(R.string.doctor_msg_termux_ok)
                            } else {
                                str(R.string.doctor_msg_termux_denied)
                            },
                        severity = if (runCommandPerm) Severity.OK else Severity.WARN,
                    ),
                )
            }
        }

    // ----- Browser (Pass 3) ------------------------------------------------------------

    /**
     * Pass 3: Doctor rows for the in-app browser feature.
     *  - `browser.profile_dir_writable` — the WebView profile lives at
     *    `${filesDir}/browser-profile/`. The directory MUST exist + be writable for cookies
     *    to persist across app restarts. AutoFix re-creates it on demand.
     *  - `browser.write_tools_status` — informational live count of which write-tools the
     *    user has switched on. Lets a user spot-check at a glance whether `browser_type`
     *    is unintentionally enabled. INFO severity, no fix action.
     *
     * The category is [DoctorCategory.Permissions] per the spec ("权限 / 服务").
     * Both rows are emitted regardless of master Browser-toggle state, but their severity
     * downgrades to INFO when no assistant has [LocalToolOption.Browser] enabled (matches
     * the existing capability-aware pattern used throughout the file).
     */
internal fun DoctorChecks.browserChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> =
        buildList {
            val needers = requirersOf(Capability.Browser, enabled)
            val browserNeeded = needers.isNotEmpty()

            // Row 1: profile dir writable (with AutoFix to mkdirs).
            val profileDir = File(context.filesDir, "browser-profile")
            val exists = runCatching { profileDir.exists() && profileDir.isDirectory }.getOrDefault(false)
            val writable = exists && runCatching { profileDir.canWrite() }.getOrDefault(false)
            val ok = exists && writable
            add(
                DoctorCheck(
                    id = "browser.profile_dir_writable",
                    category = DoctorCategory.Permissions,
                    labelRes = R.string.doctor_browser_02,
                    detail =
                        when {
                            ok && browserNeeded -> str(R.string.doctor_msg_browser_ok, profileDir.absolutePath)
                            ok -> str(R.string.doctor_msg_browser_unneeded, profileDir.absolutePath)
                            !exists && browserNeeded -> str(R.string.doctor_msg_browser_no_dir)
                            !exists -> str(R.string.doctor_msg_browser_no_dir_unneeded)
                            !writable && browserNeeded -> str(R.string.doctor_msg_browser_readonly_needed)
                            else -> str(R.string.doctor_msg_browser_readonly)
                        },
                    severity =
                        when {
                            ok -> Severity.OK
                            browserNeeded -> Severity.WARN
                            else -> Severity.INFO
                        },
                    fix = if (!ok && browserNeeded) buildBrowserProfileAutoFix(profileDir) else null,
                ),
            )

            addAll(buildBrowserWriteToolsChecks())
        }

private fun DoctorChecks.buildBrowserProfileAutoFix(profileDir: File): FixAction.AutoFix =
    FixAction.AutoFix(
        labelRes = R.string.doctor_browser_07,
        run = {
            val created = runCatching { profileDir.mkdirs() }.getOrDefault(false)
            val nowOk = profileDir.exists() && profileDir.canWrite()
            AutoFixResult(
                ok = nowOk,
                message =
                    if (nowOk) {
                        str(R.string.doctor_msg_browser_created, profileDir.absolutePath)
                    } else if (created) {
                        str(R.string.doctor_msg_browser_created_readonly)
                    } else {
                        str(R.string.doctor_msg_browser_mkdir_failed)
                    },
            )
        },
    )

private fun DoctorChecks.buildBrowserWriteToolsChecks(): List<DoctorCheck> = buildList {
            // Row 2: write-tools live count (INFO only). Skipped silently if BrowserPreferences
            // wasn't injected — the row is purely informational and the test harness paths
            // that don't construct prefs shouldn't fail.
            val prefs = browserPreferences
            if (prefs != null) {
                val snapshot =
                    runCatching { prefs.snapshotBlocking() }.getOrDefault(
                        BrowserToolDefaults.DEFAULT_ENABLED,
                    )
                val onWriteTools = BrowserToolDefaults.WRITE_TOOLS.filter { snapshot[it] == true }
                val detail =
                    if (onWriteTools.isEmpty()) {
                        str(R.string.doctor_msg_browser_write_none)
                    } else {
                        str(
                            R.string.doctor_msg_browser_write_some,
                            onWriteTools.size,
                            onWriteTools.joinToString(", ") { it.removePrefix("browser_") },
                        )
                    }
                add(
                    DoctorCheck(
                        id = "browser.write_tools_status",
                        category = DoctorCategory.Permissions,
                        labelRes = R.string.doctor_browser_11,
                        detail = detail,
                        severity = Severity.INFO,
                    ),
                )
            }
        }

    // ----- Maintenance -----------------------------------------------------------------

internal fun DoctorChecks.maintenanceChecks(): List<DoctorCheck> =
        buildList {
            // Cache size on disk
            val cacheBytes = directorySize(context.cacheDir)
            add(
                DoctorCheck(
                    id = "maint.cache_size",
                    category = DoctorCategory.Maintenance,
                    labelRes = R.string.doctor_maint_01,
                    detail =
                        str(R.string.doctor_msg_cache_usage, humanBytes(cacheBytes)) +
                            if (cacheBytes > 200L * 1024 * 1024) str(R.string.doctor_msg_cache_advice) else str(R.string.doctor_msg_cache_normal),
                    severity = if (cacheBytes > 500L * 1024 * 1024) Severity.WARN else Severity.OK,
                    fix =
                        FixAction.AutoFix(
                            labelRes = R.string.doctor_maint_05,
                            run = {
                                val freed = clearDirectoryContents(context.cacheDir)
                                AutoFixResult(ok = true, message = str(R.string.doctor_msg_cache_freed, humanBytes(freed)))
                            },
                        ),
                ),
            )
        }

    // ----- Diagnostics summary ---------------------------------------------------------

internal fun DoctorChecks.diagnosticsChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> =
        listOf(
            DoctorCheck(
                id = "diag.app",
                category = DoctorCategory.Diagnostics,
                labelRes = R.string.doctor_diag_01,
                detail = "RikkaHub Agents ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — debug=${BuildConfig.DEBUG}",
                severity = Severity.INFO,
            ),
            DoctorCheck(
                id = "diag.android",
                category = DoctorCategory.Diagnostics,
                labelRes = R.string.doctor_common_19,
                detail = str(R.string.doctor_msg_android_device, Build.VERSION.SDK_INT, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL),
                severity = Severity.INFO,
            ),
            DoctorCheck(
                id = "diag.runtime",
                category = DoctorCategory.Diagnostics,
                labelRes = R.string.doctor_diag_03,
                detail =
                    run {
                        val rt = Runtime.getRuntime()
                        val freeMb = rt.freeMemory() / (1024 * 1024)
                        val totalMb = rt.totalMemory() / (1024 * 1024)
                        val maxMb = rt.maxMemory() / (1024 * 1024)
                        str(R.string.doctor_msg_heap, freeMb, totalMb, maxMb)
                    },
                severity = Severity.INFO,
            ),
            DoctorCheck(
                            id = "diag.enabled_tools",
                            category = DoctorCategory.Diagnostics,
                            labelRes = R.string.doctor_diag_05,
                            detail =
                                if (enabled.isEmpty()) {
                                    str(R.string.doctor_msg_no_local_tools)
                                } else {
                                    str(R.string.doctor_msg_tool_groups, enabled.size)
                                },
                            severity = if (enabled.isEmpty()) Severity.WARN else Severity.INFO,
                        ),
                        // P0：AppLog 开关真相——默认关闭时 read_app_logs 永远返回空
                        DoctorCheck(
                            id = "diag.app_log_enabled",
                            category = DoctorCategory.Diagnostics,
                            labelRes = R.string.doctor_diag_02,
                            detail =
                                if (AppLog.isEnabled(context)) {
                                    str(R.string.doctor_msg_app_log_on)
                                } else {
                                    str(R.string.doctor_msg_app_log_off)
                                },
                            severity = if (AppLog.isEnabled(context)) Severity.OK else Severity.WARN,
                        ),
                        // P1：崩溃记录——ApplicationExitInfo 最近 N 天崩溃汇总
                        run {
                            val exitInfo = crashExitSummary()
                            val days = 7
                            DoctorCheck(
                                id = "diag.crash_history",
                                category = DoctorCategory.Diagnostics,
                                labelRes = R.string.doctor_diag_04,
                                detail =
                                    if (exitInfo == null) {
                                        str(R.string.doctor_msg_crash_none, days)
                                    } else {
                                        val fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US).withZone(java.time.ZoneId.systemDefault())
                                        str(
                                            R.string.doctor_msg_crash_some,
                                            exitInfo.count,
                                            days,
                                            fmt.format(java.time.Instant.ofEpochMilli(exitInfo.latestTs)),
                                        )
                                    },
                                severity = if (exitInfo == null) Severity.OK else Severity.WARN,
                            )
                        }
        ) + buildExtendedDiagnosticsChecks()

private fun DoctorChecks.buildExtendedDiagnosticsChecks(): List<DoctorCheck> =
        listOf(
                        // P1：版本/进程新鲜度——装了新包但进程跑旧代码
                                    run {
                                        val installed = runCatching {
                                            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                                        }.getOrNull()
                                        val running = BuildConfig.VERSION_CODE.toLong()
                                        val fresh = installed != null && installed == running
                                        DoctorCheck(
                                            id = "diag.version_freshness",
                                            category = DoctorCategory.Diagnostics,
                                            labelRes = R.string.doctor_diag_06,
                                            detail =
                                                if (fresh) {
                                                    str(R.string.doctor_msg_version_ok, running)
                                                } else {
                                                    str(R.string.doctor_msg_version_stale, installed ?: -1, running)
                                                },
                                            severity = if (fresh) Severity.OK else Severity.WARN,
                                        )
                                    },
                                    // P2：技能注入真相——「开关看似开实则空」：扫描 skills 目录下 SKILL.md 存在且非空；
                                                //     auto_load 技能还校验 auto_load_path 文件存在（注入是否真正有效）。
                                                //     纯文件系统自检（不依赖 settingsStore，非 suspend 环境），只统计本地技能目录。
                                                run {
                                                    val skillsDir = context.filesDir.resolve(me.rerere.rikkahub.data.files.FileFolders.SKILLS)
                                                    val dirs =
                                                        skillsDir.listFiles()
                                                            ?.filter { it.isDirectory }
                                                            ?: emptyList()
                                                    val problems =
                                                        dirs.filter { dir ->
                                                            val skillFile = dir.resolve("SKILL.md")
                                                            if (!skillFile.exists() || skillFile.length() == 0L) {
                                                                true
                                                            } else {
                                                                val fm = runCatching {
                                                                    me.rerere.rikkahub.data.files.SkillFrontmatterParser.parse(skillFile.readText())
                                                                }.getOrDefault(
                                                                    me.rerere.rikkahub.data.files.SkillFrontmatter.Empty,
                                                                )
                                                                val autoLoad = fm["auto_load"]?.equals("true", ignoreCase = true) == true
                                                                val path = fm["auto_load_path"]
                                                                autoLoad && !path.isNullOrBlank() &&
                                                                    (!dir.resolve(path).exists() || dir.resolve(path).length() == 0L)
                                                            }
                                                        }.map { it.name }
                                                    DoctorCheck(
                                                        id = "diag.skill_injection",
                                                        category = DoctorCategory.Diagnostics,
                                                        labelRes = R.string.doctor_diag_07,
                                                        detail =
                                                            if (dirs.isEmpty()) {
                                                                str(R.string.doctor_msg_skill_none_on_disk)
                                                            } else if (problems.isEmpty()) {
                                                                str(R.string.doctor_msg_skill_all_present, dirs.size)
                                                            } else {
                                                                str(R.string.doctor_msg_skill_missing, problems.take(5).joinToString(", "))
                                                            },
                                                        severity = if (problems.isEmpty()) Severity.OK else Severity.WARN,
                                                    )
                                                }
        ) + buildRedactorAndLogDiagnosticsChecks()

private fun DoctorChecks.buildRedactorAndLogDiagnosticsChecks(): List<DoctorCheck> =
        listOf(
                                                // P2：脱敏回归自检——已知样例喂 maskText，断言被掩；只防回归，不证明覆盖完整
                                                run {
                                                    val samples =
                                                        listOf(
                                                            "sk-1234567890abcdef1234567890abcdef",
                                                            "xai-1234567890abcdef1234567890abcdef",
                                                            "Bearer abcdefghijklmnopqrstuvwxyz012345",
                                                            "AIzaSyAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                                            "api_key=9876543210abcdef9876543210abcdef", // gitleaks:allow
                                                        )
                                                    val leaked =
                                                        samples.filter { sample ->
                                                            val masked = me.rerere.rikkahub.utils.LogRedactor.maskText(sample)
                                                            masked == sample
                                                        }
                                                    DoctorCheck(
                                                        id = "diag.redactor_selftest",
                                                        category = DoctorCategory.Diagnostics,
                                                        labelRes = R.string.doctor_diag_08,
                                                        detail =
                                                            if (leaked.isEmpty()) {
                                                                str(R.string.doctor_msg_redactor_ok, samples.size)
                                                            } else {
                                                                str(R.string.doctor_msg_redactor_leak, leaked.take(3).joinToString(", "))
                                                            },
                                                        severity = if (leaked.isEmpty()) Severity.OK else Severity.WARN,
                                                    )
                                                },
                                                // P3：日志健康——AppLog 近期 WARN/ERROR 占比与小样（过脱敏）
                                                run {
                                                    val logs = me.rerere.rikkahub.data.log.AppLog.getLogs()
                                                    val recent = logs.takeLast(100)
                                                    val problems = recent.filter { it.level == 'W' || it.level == 'E' }
                                                    DoctorCheck(
                                                        id = "diag.log_health",
                                                        category = DoctorCategory.Diagnostics,
                                                        labelRes = R.string.doctor_diag_09,
                                                        detail =
                                                            if (recent.isEmpty()) {
                                                                str(R.string.doctor_msg_log_health_empty)
                                                            } else {
                                                                val sample =
                                                                    problems.takeLast(3).joinToString(" | ") { e ->
                                                                        "${e.level} ${e.tag}: ${me.rerere.rikkahub.utils.LogRedactor.maskText(e.message.take(80))}"
                                                                    }
                                                                str(
                                                                    R.string.doctor_msg_log_health_summary,
                                                                    problems.size,
                                                                    recent.size,
                                                                    sample.ifEmpty { "-" },
                                                                )
                                                            },
                                                        severity =
                                                            when {
                                                                problems.isEmpty() -> Severity.OK
                                                                problems.size >= 20 -> Severity.WARN
                                                                else -> Severity.INFO
                                                            },
                                                    )
                                                },
                                            )

    // P1 检查项 `diag.crash_history` 的辅助：汇总最近 N 天非正常退出（崩溃）次数与最新时间。
    // ApplicationExitInfo 仅 API 30+；低版本返回 null（检查项显示"无崩溃"）。
private fun DoctorChecks.crashExitSummary(): CrashExitSummary? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val exits =
            runCatching {
                am.getHistoricalProcessExitReasons(context.packageName, 0, 20)
            }.getOrDefault(emptyList())
        val crashes =
            exits.filter { e ->
                (e.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                    e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    e.reason == android.app.ApplicationExitInfo.REASON_ANR) &&
                    e.timestamp >= cutoff
            }
        if (crashes.isEmpty()) return null
        return CrashExitSummary(count = crashes.size, latestTs = crashes.maxOf { it.timestamp })
    }

internal data class CrashExitSummary(val count: Int, val latestTs: Long)

private fun directorySize(dir: File): Long =
        runCatching {
            if (!dir.exists()) return@runCatching 0L
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

private fun clearDirectoryContents(dir: File): Long {
        var freed = 0L
        runCatching {
            dir.listFiles()?.forEach { f ->
                freed += directorySize(f)
                f.deleteRecursively()
            }
        }
        return freed
    }

private fun humanBytes(bytes: Long): String {
        val mb = 1024.0 * 1024
        val gb = mb * 1024
        return when {
            bytes < mb -> "%.0f KB".format(bytes / 1024.0)
            bytes < gb -> "%.1f MB".format(bytes / mb)
            else -> "%.2f GB".format(bytes / gb)
        }
    }


/**
 * Pure decision logic backing the "net.llamacpp_models" row: given the filename ->
 * absolute-path map from [me.rerere.locallm.LocalRuntimePreferences.installedModels],
 * report the total installed count and which filenames' backing file is no longer on
 * disk. Extracted to a top-level function (rather than left inline) so it's unit-testable
 * on the JVM without an Android Context — [DoctorChecks] itself needs one for every other
 * check, which rules out constructing it directly in a plain JUnit test.
 */
internal data class LlamaCppModelStatus(val total: Int, val missing: List<String>)

internal fun llamaCppModelStatus(installed: Map<String, String>): LlamaCppModelStatus =
    LlamaCppModelStatus(
        total = installed.size,
        missing = installed.filterValues { path -> !File(path).exists() }.keys.sorted(),
    )

/**
 * Pure decision logic backing the "storage.gallery_orphans" row: given the resolved
 * absolute paths of every generated-image DB record, report the total and how many no
 * longer have a backing file on disk (the #39 bug class). Mirrors [llamaCppModelStatus]'s
 * shape so both are unit-testable on the JVM without a Context.
 */
internal data class GalleryOrphanStatus(val total: Int, val orphanCount: Int)

internal fun galleryOrphanStatus(absolutePaths: List<String>): GalleryOrphanStatus =
    GalleryOrphanStatus(
        total = absolutePaths.size,
        orphanCount = absolutePaths.count { path -> !File(path).exists() },
    )

/**
 * Pure decision logic backing the "assistant.subagent_profiles" row: which configured
 * [SubAgentProfile]s have a `modelId` that no longer resolves to a chat model of an
 * enabled provider (the #28 failure class; it used to fail silently at dispatch time).
 * Reuses [SubAgentModelResolver.resolve] itself rather than re-deriving model lookup; a
 * profile's `modelId` is already a resolved [kotlin.uuid.Uuid], so it's passed through as
 * the resolver's string input, exactly like a `subagent_dispatch` caller would.
 */
internal data class SubAgentProfileStatus(val total: Int, val broken: List<String>)

internal fun subAgentProfileStatus(
    profiles: List<SubAgentProfile>,
    providers: List<ProviderSetting>,
): SubAgentProfileStatus = SubAgentProfileStatus(
    total = profiles.size,
    broken = profiles.filter { profile ->
        val modelId = profile.modelId ?: return@filter false
        SubAgentModelResolver.resolve(modelId.toString(), providers) is SubAgentModelResolver.Result.Failed
    }.map { it.name },
)

/**
 * Pure decision logic backing the "service.mcp_servers" row: given each configured
 * server's (name, enabled, connected) triple, report the configured/enabled/connected
 * counts and which enabled servers are not currently connected.
 */
internal data class McpServerSummary(
    val configured: Int,
    val enabled: Int,
    val connected: Int,
    val enabledNotConnected: List<String>,
)

internal fun mcpServerSummary(servers: List<Triple<String, Boolean, Boolean>>): McpServerSummary =
    McpServerSummary(
        configured = servers.size,
        enabled = servers.count { (_, enabled, _) -> enabled },
        connected = servers.count { (_, _, connected) -> connected },
        enabledNotConnected = servers.filter { (_, enabled, connected) -> enabled && !connected }
            .map { (name, _, _) -> name },
    )

/**
 * Pure decision logic backing the "skills.seed" row: a bundled skill's on-disk
 * `.core-bundled-hash` sentinel is stale when it's missing, unreadable, or doesn't match
 * the hash of what the app would currently seed. Non-bundled (user-added) entries are
 * never flagged: [isBundled] gates them out entirely, mirroring
 * [me.rerere.rikkahub.data.files.decideSeedAction]'s "never touch a directory we didn't
 * create" rule.
 */
internal data class SkillSeedEntry(
    val name: String,
    val isBundled: Boolean,
    val storedHash: String?,
    val currentHash: String?,
)

internal fun staleSeedSkillNames(entries: List<SkillSeedEntry>): List<String> =
    entries.filter { it.isBundled && it.storedHash != it.currentHash }.map { it.name }
