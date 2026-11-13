#!/system/bin/sh
# chroot runtime helper (Branch A) — private mount namespace + true mounts + chroot.
# Runs under `su -c` on rooted Android devices.
#
# Usage: su -c 'sh chroot-run.sh <rootfs_dir> <command...>'
#   chroot-run.sh <dir> shell          → interactive shell inside chroot (private mount ns)
#   chroot-run.sh <dir> exec <cmd...>  → run one command inside chroot (private mount ns)
#   chroot-run.sh <dir> prepare        → one-time rootfs DNS/PATH setup + mount check
#   chroot-run.sh <dir> unmount        → fallback recursive unmount & .chroot-* cleanup
#
# Architecture & safety guarantees (2026-11-08 Branch A):
#   1. Private mount namespace: enters `unshare -m` and immediately sets
#      `mount --make-rprivate /` (toybox 0.8.9 unshare lacks `--propagation`).
#      When the command/shell exits, the kernel destroys the mount namespace
#      automatically — leaving 0 mount entries in the host `/proc/mounts`.
#   2. No `/proc/mounts` string-matching to skip mounts: `/data/user/0` vs `/data/data`
#      path aliases and proot visibility cause false positives. Fresh mounts are
#      performed inside each isolated mount namespace, backed by an EXIT/INT/TERM trap.
#   3. Non-destructive switching: rootfs (`<workspace>/linux`) and workspace files
#      (`<workspace>/files`) are shared with proot without altering existing files or
#      leaving `.chroot-*` state markers behind.

ROOTFS="$1"
shift
[ -d "$ROOTFS" ] || { echo "ERR: rootfs dir not found: $ROOTFS" >&2; exit 2; }
# containment：ROOTFS 必须是工作区下的 linux 目录（<base>/workspaces/<root>/linux），
# 否则 unmount 分支的 umount -R 可能卸掉调用方命名空间里的任意目录
ROOTFS_CANON=$(cd "$ROOTFS" 2>/dev/null && pwd) || { echo "ERR: cannot resolve rootfs dir: $ROOTFS" >&2; exit 2; }
case "$ROOTFS_CANON" in
    */workspaces/*/linux) ROOTFS="$ROOTFS_CANON" ;;
    *) echo "ERR: rootfs dir outside workspace: $ROOTFS" >&2; exit 2 ;;
esac
[ "$(id -u)" = "0" ] || { echo "ERR: not root" >&2; exit 2; }

CHROOT_PATH="/workspace/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
WORKDIR="${CHROOT_WORKDIR:-/workspace}"
export CHROOT_DNS="${CHROOT_DNS:-}"

HOST_SH="/system/bin/sh"
[ -x "$HOST_SH" ] || HOST_SH="/bin/sh"

# Enter an isolated mount namespace so all mounts self-destruct on process exit.
# Toybox 0.8.9 `unshare` does not support `--propagation`, so we use `unshare -m`
# followed by `mount --make-rprivate /` inside the new namespace.
enter_private_ns() {
    if [ "${_CHROOT_IN_NS:-0}" != "1" ]; then
        # 无 unshare 时不得回退到宿主挂载命名空间挂载：挂载会漏进宿主，SIGKILL 还会漏卸载
        if ! command -v unshare >/dev/null 2>&1; then
            echo "ERR: unshare not found, refusing to mount in host namespace" >&2
            exit 1
        fi
        export _CHROOT_IN_NS=1
        exec unshare -m "$HOST_SH" "$0" "$ROOTFS" "$@"
    fi
    # toybox 0.8.9 的 mount 不支持传播位选项：--make-rprivate / --make-private 会报 bad /etc/fstab，
    # `-o remount,rprivate` 返回成功但 mountinfo 里的 master: 不变（静默无效）。
    # Android 的 / 本身是 slave 挂载（mountinfo 带 master:），只单向接收传播，unshare -m 内的挂载
    # 不会出现在宿主 /proc/self/mountinfo —— 隔离目的已成立，故不可设 private 时不中止挂载流程。
    mount --make-rprivate / 2>/dev/null || mount --make-private / 2>/dev/null || \
        echo "warn: mount propagation cannot be set private here, relying on slave semantics" >&2
}

do_unmount_quiet() {
    # 按层级分批并行卸载：子挂载点必须先于父挂载点摘，同级可以并行。
    # ⚠ 以后新增挂载点必须放进对应层级的批次里（先子后父）：toybox 的 umount -l 对有
    #   子挂载的父挂载点仍会报 Invalid argument（dev / workspace 均如此）。
    # 串行 13 次 umount 每次都是 fork+exec，分批并行可显著缩短退出耗时。
    # 注：toybox 的 umount 不支持 -R（会报 Unknown option），此行从未生效，已删。
    for m in \
        "$ROOTFS/workspace/app-databases" \
        "$ROOTFS/workspace/app-shared-prefs"; do
        ( umount "$m" 2>/dev/null || umount -l "$m" 2>/dev/null || true ) &
    done
    wait
    for m in \
        "$ROOTFS/workspace" \
        "$ROOTFS/skills" \
        "$ROOTFS/tool_outputs" \
        "$ROOTFS/upload" \
        "$ROOTFS/mnt/shared" \
        "$ROOTFS/tmp" \
        "$ROOTFS/dev/shm" \
        "$ROOTFS/dev/pts"; do
        ( umount "$m" 2>/dev/null || umount -l "$m" 2>/dev/null || true ) &
    done
    wait
    for m in \
        "$ROOTFS/dev" \
        "$ROOTFS/sys" \
        "$ROOTFS/proc"; do
        ( umount "$m" 2>/dev/null || umount -l "$m" 2>/dev/null || true ) &
    done
    wait
    # 只清 .chroot-mounted：它有清理契约（切回 proot 后必须不残留，见 workspace 侧
    # WorkspaceBackendSwitchTest 的断言），也兼容旧版本留下的文件。
    # .chroot-fixed / .chroot-lock 历史上从无写入点（只在旧清理名单里挂着），已去掉。
    rm -rf "$ROOTFS"/.chroot-mounted 2>/dev/null || true
}

do_unmount() {
    do_unmount_quiet
    rm -rf "$ROOTFS"/.chroot-* 2>/dev/null || true
    echo "unmounted clean"
    return 0
}

# 额外绑定（与 proot 侧 bindMounts 对齐）：源不存在就跳过，失败只告警不阻断。
# 失败计数由 do_mounts 汇总打印，缺失的绑定目录会让后续命令以难定位的方式失败。
BIND_FAILED=0
bind_extra() { # $1=app_dir $2=相对源 $3=chroot 内目标
    [ -d "$1/$2" ] || return 0
    mkdir -p "$ROOTFS$3" 2>/dev/null
    mount --bind "$1/$2" "$ROOTFS$3" 2>/dev/null || {
        echo "WARN: bind $3 failed" >&2
        BIND_FAILED=$((BIND_FAILED + 1))
    }
    return 0
}

# 清理被打断的命令留下的命令文件。
# Kotlin 侧在 finally 里删 .chroot-exec-<uuid>，但进程被外部杀掉（超时/预算掐断/用户取消）时
# 那个 finally 不会执行，文件就留在 rootfs 根下累积。只删 1 小时前的，避免误删并发命令正在用的那个。
purge_stale_exec_files() {
    now=$(date +%s 2>/dev/null || echo 0)
    [ "$now" -gt 0 ] || return 0
    for f in "$ROOTFS"/.chroot-exec-*; do
        [ -f "$f" ] || continue
        mt=$(stat -c %Y "$f" 2>/dev/null || echo "$now")
        [ $((now - mt)) -gt 3600 ] && rm -f "$f" 2>/dev/null
    done
    return 0
}

# exec / shell 退出前的增量属主修复。
# chroot 内以 root 写的文件在宿主上属主是 0:0；切回 proot 后 app uid 无法覆盖/删除它们。
# 旧实现只在“切换后端”时跑 fixowner，App 被杀/重启/直接停用后这些文件就永久留着
# 这些文件会一直留着，直到手工 chown 才恢复。
# 开销控制：先 find -print -quit 探一下，没有 root 文件就立刻返回（空跑通常 <20ms）。
fixowner_quick() {
    [ "${CHROOT_FIXOWNER_ON_EXIT:-1}" = "1" ] || return 0
    files_src="${CHROOT_FILES_SRC:-$ROOTFS/../files}"
    [ -d "$files_src" ] || return 0
    appuid=$(stat -c %u "$ROOTFS/../.." 2>/dev/null) || return 0
    [ -n "$appuid" ] || return 0
    [ -n "$(find -P "$files_src" -user 0 -print -quit 2>/dev/null)" ] || return 0
    find -P "$files_src" -user 0 -exec chown -h "$appuid:$appuid" {} + 2>/dev/null
    return 0
}

# 本 rootfs 名下是否已有挂载（上一会话被 SIGKILL 留下的残留）。
# 同一目录在 mountinfo 里会出现三种写法（/data/user/0、/data/data、
# /data_mirror/data_ce/null/0），取决于挂载当时用的是哪个路径；按前缀同时匹配
# 三种变体，避免单一字面量漏判（漏判的后果是 mount -t proc 直接 EBUSY、工作区不可用）。
has_residual_mounts() {
    local sub alt1 alt2 alt3
    case "$ROOTFS" in
        /data/user/0/*) sub="${ROOTFS#/data/user/0/}" ;;
        /data/data/*) sub="${ROOTFS#/data/data/}" ;;
        /data_mirror/data_ce/null/0/*) sub="${ROOTFS#/data_mirror/data_ce/null/0/}" ;;
        *) sub="" ;;
    esac
    if [ -n "$sub" ]; then
        alt1="/data/user/0/$sub"
        alt2="/data/data/$sub"
        alt3="/data_mirror/data_ce/null/0/$sub"
    else
        alt1="$ROOTFS"
        alt2="$ROOTFS"
        alt3="$ROOTFS"
    fi
    [ -r /proc/self/mountinfo ] || return 1
    awk -v a="$ROOTFS" -v b="$alt1" -v c="$alt2" -v d="$alt3" '
        { p = $5 }
        p == a || p == b || p == c || p == d { found = 1; exit }
        index(p, a "/") == 1 || index(p, b "/") == 1 || index(p, c "/") == 1 || index(p, d "/") == 1 { found = 1; exit }
        END { exit(found ? 0 : 1) }
    ' /proc/self/mountinfo 2>/dev/null
}

# 挂载完整性自检：只校验**硬必备**的六个挂载点（proc/sys/dev/dev/pts/tmp/workspace）。
# 不校验总数：可选 bind（skills / tool_outputs / upload / mnt/shared / app-databases /
# app-shared-prefs）随源目录存在与否增减，写死条数会误报；缺必备项才是真异常
# （典型成因：挂载被路径别名漏判、或静默失败）。
verify_mounts() {
    local missing="" m
    for m in proc sys dev dev/pts tmp workspace; do
        awk -v base="$ROOTFS" -v rel="$m" '
            BEGIN { want = base "/" rel }
            $5 == want { found = 1; exit }
            END { exit(found ? 0 : 1) }
        ' /proc/self/mountinfo 2>/dev/null || missing="$missing $m"
    done
    [ -n "$missing" ] && echo "WARN: required mounts missing:${missing} (commands may misbehave)" >&2
    return 0
}

do_mounts() {
    mkdir -p "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev/pts" "$ROOTFS/tmp" "$ROOTFS/workspace"
    # 幂等自愈：上一会话被 SIGKILL 时 trap 不执行，残留挂载会被新会话的 unshare 复制进来，
    # 随后 mount -t proc 会因 EBUSY 退出，该工作区将无法再用，只能手工清理挂载后恢复。
    # 开挂前先看本 rootfs 名下是否已有挂载，有就先摘干净（路径写法不唯一，见 has_residual_mounts）。
    has_residual_mounts && do_unmount_quiet
    mount -t proc proc "$ROOTFS/proc" 2>/dev/null || {
        # 兜底自愈：路径别名或检测遗漏时，挂载失败也要先清残留再重试一次，别直接放弃。
        do_unmount_quiet
        mount -t proc proc "$ROOTFS/proc" || { echo "ERR: mount $ROOTFS/proc failed" >&2; return 1; }
    }
    mount -t sysfs sysfs "$ROOTFS/sys" 2>/dev/null \
        || mount -t sysfs sys "$ROOTFS/sys" \
        || { echo "ERR: mount $ROOTFS/sys failed" >&2; return 1; }
    mount --bind /dev "$ROOTFS/dev" || { echo "ERR: mount $ROOTFS/dev failed" >&2; return 1; }
    mkdir -p "$ROOTFS/dev/pts"
    mount -t devpts devpts "$ROOTFS/dev/pts" 2>/dev/null \
        || mount --bind /dev/pts "$ROOTFS/dev/pts" 2>/dev/null \
        || { echo "ERR: mount $ROOTFS/dev/pts failed" >&2; return 1; }
    mount -t tmpfs tmpfs "$ROOTFS/tmp" || { echo "ERR: mount $ROOTFS/tmp failed" >&2; return 1; }
    # /dev/shm：Android 的 /dev 里没有 shm，而 python multiprocessing(POSIX 信号量)、
    # JVM、PostgreSQL/Redis 隐形依赖它，缺失时报的是难定位的 "No such file or directory"。
    # 独立 tmpfs（1777）顺带把共享内存和 /tmp 隔开，避免 /tmp 被大文件占满后 shm 一起炸。
    mkdir -p -m 1777 "$ROOTFS/dev/shm" 2>/dev/null
    chmod 1777 "$ROOTFS/dev/shm" 2>/dev/null   # 已存在的旧目录 mkdir -p 不改 mode，这里强制校正
    mount -t tmpfs -o mode=1777,size=256m tmpfs "$ROOTFS/dev/shm" 2>/dev/null \
        || echo "WARN: mount $ROOTFS/dev/shm failed (python multiprocessing / JVM may break)" >&2
    if [ -d "$ROOTFS/../files" ]; then
        mount --bind "$ROOTFS/../files" "$ROOTFS/workspace" || {
            echo "ERR: mount $ROOTFS/workspace failed" >&2
            return 1
        }
    fi
    # 与 proot 侧 bindMounts 对齐：这些路径在 proot 下是 bind 进来的，
    # chroot 侧缺失就会"切过去功能少一块"（技能库/上传/工具输出/共享目录/App 数据库）。
    # 源 = app filesDir 下的相对路径（$ROOTFS = filesDir/workspaces/<root>/linux）。
    app_dir=$(cd "$ROOTFS/../../.." 2>/dev/null && pwd)
    if [ -n "$app_dir" ]; then
        bind_extra "$app_dir" skills /skills
        bind_extra "$app_dir" tool_outputs /tool_outputs
        bind_extra "$app_dir" upload /upload
        bind_extra "$app_dir" shared /mnt/shared
        bind_extra "$app_dir" databases /workspace/app-databases
        bind_extra "$app_dir" shared_prefs /workspace/app-shared-prefs
    fi
    # proot 侧 bindMounts 必建这两个挂载点；chroot 侧源目录不存在时 bind_extra 直接 return，
    # 于是 /workspace/app-databases 这类路径在两侧行为不一致（探针/脚本会报缺失）。补空目录对齐。
    mkdir -p "$ROOTFS/workspace/app-databases" "$ROOTFS/workspace/app-shared-prefs" 2>/dev/null
    if [ "$BIND_FAILED" -gt 0 ]; then
        echo "WARN: $BIND_FAILED optional bind mount(s) failed (see WARN lines above)" >&2
    fi
    verify_mounts
    return 0
}

# DNS & PATH setup:
# - Ensures $ROOTFS/etc/resolv.conf is a real file (removes broken symlinks).
# - If CHROOT_DNS is non-empty (custom mode), writes those nameservers.
# - Otherwise (auto mode), reads live system DNS from /proc/net/pnp or getprop
#   net.dns1/net.dns2 (excluding loopback 127.* and ::1), falling back to
#   223.5.5.5 / 119.29.29.29 when unavailable.
do_fixes() {
    mkdir -p "$ROOTFS/etc" || return 1
    # rootfs 的 /tmp 若在历史会话里被建成 755 root，proot 后端下将整体不可写（app uid 改不了），
    # 所有依赖 TMPDIR 的工具/安装器全部失败。每次会话校正一次 mode，切回 proot 后即恢复。
    [ -d "$ROOTFS/tmp" ] && chmod 1777 "$ROOTFS/tmp" 2>/dev/null
    [ -L "$ROOTFS/etc/resolv.conf" ] && rm -f "$ROOTFS/etc/resolv.conf"
    if [ -n "${CHROOT_DNS:-}" ]; then
        printf '# Generated by RikkaHub workspace (mode=custom).\n' > "$ROOTFS/etc/resolv.conf" || return 1
        for ns in $CHROOT_DNS; do
            case "$ns" in
                ""|127.*|0.*|::1|::|*[!0-9a-fA-F.:]*) ;;
                *) printf 'nameserver %s\n' "$ns" >> "$ROOTFS/etc/resolv.conf" || return 1 ;;
            esac
        done
    else
        sys_dns=""
        if [ -r /proc/net/pnp ]; then
            for ns in $(awk '/^nameserver[[:space:]]+/ {print $2}' /proc/net/pnp 2>/dev/null); do
                case "$ns" in
                    ""|127.*|0.*|::1|::|*[!0-9a-fA-F.:]*) ;;
                    *) sys_dns="$sys_dns $ns" ;;
                esac
            done
        fi
        if [ -z "$sys_dns" ] && command -v getprop >/dev/null 2>&1; then
            for prop in net.dns1 net.dns2 net.dns3 net.dns4; do
                ns=$(getprop "$prop" 2>/dev/null)
                case "$ns" in
                    ""|127.*|0.*|::1|::|*[!0-9a-fA-F.:]*) ;;
                    *) sys_dns="$sys_dns $ns" ;;
                esac
            done
        fi
        if [ -n "$sys_dns" ]; then
            printf '# Generated by RikkaHub workspace (mode=auto).\n' > "$ROOTFS/etc/resolv.conf" || return 1
            for ns in $sys_dns; do
                printf 'nameserver %s\n' "$ns" >> "$ROOTFS/etc/resolv.conf" || return 1
            done
        elif [ ! -s "$ROOTFS/etc/resolv.conf" ] || grep -q 'mode=custom' "$ROOTFS/etc/resolv.conf" 2>/dev/null; then
            printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > "$ROOTFS/etc/resolv.conf" || return 1
        fi
    fi
    mkdir -p "$ROOTFS/etc/profile.d" || return 1
    # 00-path.sh 跟随软链会写到 rootfs 外：先删链接再写普通文件（与 resolv.conf 同口径）
    [ -L "$ROOTFS/etc/profile.d/00-path.sh" ] && rm -f "$ROOTFS/etc/profile.d/00-path.sh"
    if [ ! -s "$ROOTFS/etc/profile.d/00-path.sh" ]; then
        printf 'export PATH=%s\n' "$CHROOT_PATH" > "$ROOTFS/etc/profile.d/00-path.sh" || return 1
    fi
    return 0
}

run_in_chroot() {
    if [ -x "$ROOTFS/usr/bin/env" ]; then
        chroot "$ROOTFS" /usr/bin/env -i \
            HOME=/root \
            TERM="${TERM:-xterm-256color}" \
            PATH="$CHROOT_PATH" \
            "$@"
    else
        PATH="$CHROOT_PATH" HOME=/root TERM="${TERM:-xterm-256color}" \
            chroot "$ROOTFS" "$@"
    fi
}

case "$1" in
    prepare)
        enter_private_ns prepare
        trap 'do_unmount_quiet' EXIT INT TERM
        do_fixes || exit 1
        do_mounts || exit 1
        echo "ready: $ROOTFS"
        ;;
    unmount)
        do_unmount
        ;;
    fixowner)
        # 数据区属主修复（§3 无损切换）：chroot 以 root 写过的文件属主 0:0，
        # 切回 proot 后 app uid 读写会失败。范围只碰数据区（HOME=/root 与 /workspace），
        # rootfs 系统目录本就该是 0:0，不动。app uid 取自 App 私有 data 目录属主。
        appuid=$(stat -c %u "$ROOTFS/../.." 2>/dev/null || echo "")
        [ -n "$appuid" ] || { echo "ERR: cannot resolve app uid" >&2; exit 1; }
        # 数据区在 rootfs 的兄弟目录 files（挂载到 chroot 内 /workspace），
        # 而 $ROOTFS/workspace 只是未挂载时的空挂载点 → 必须扫真实数据目录。
        files_src="${CHROOT_FILES_SRC:-$ROOTFS/../files}"
        # CHROOT_FILES_SRC 未校验会改 chown 目标：只接受 workspace 的 files 目录（默认）
        # 或 rootfs 内的路径，其余一律拒绝；目录不存在时由下面的循环跳过，不在这里报错
        if [ -d "$files_src" ]; then
            files_canon=$(cd "$files_src" 2>/dev/null && pwd) || { echo "ERR: cannot resolve CHROOT_FILES_SRC: $files_src" >&2; exit 1; }
            workspace_canon=$(cd "$ROOTFS/.." 2>/dev/null && pwd) || { echo "ERR: cannot resolve workspace dir" >&2; exit 1; }
            case "$files_canon" in
                "$workspace_canon/files"|"$ROOTFS"|"$ROOTFS"/*) files_src="$files_canon" ;;
                *) echo "ERR: CHROOT_FILES_SRC outside workspace: $files_src" >&2; exit 1 ;;
            esac
        fi
        n=0
        for area in "$ROOTFS/root" "$files_src"; do
            [ -d "$area" ] || continue
            before=$(find -P "$area" -user 0 2>/dev/null | wc -l)
            if [ "$before" -gt 0 ]; then
                find -P "$area" -user 0 -exec chown -h "$appuid:$appuid" {} + 2>/dev/null
            fi
            after=$(find -P "$area" -user 0 2>/dev/null | wc -l)
            n=$((n + before - after))
            [ "$after" -gt 0 ] && echo "WARN: $area still has $after zero-owner files" >&2
        done
        echo "fixowner: fixed zero-owner files = $n (uid=$appuid)" >&2
        ;;
    shell)
        enter_private_ns shell
        trap 'do_unmount_quiet' EXIT INT TERM
        do_fixes || exit 1
        do_mounts || exit 1
        if [ -x "$ROOTFS/bin/bash" ]; then
            run_in_chroot /bin/bash -l
        else
            run_in_chroot /bin/sh -l
        fi
        rc=$?
        fixowner_quick        # 交互 shell 退出后同样收尾
        exit $rc
        ;;
    exec)
        shift
        enter_private_ns exec "$@"
        trap 'do_unmount_quiet' EXIT INT TERM
        do_fixes || exit 1
        do_mounts || exit 1
        purge_stale_exec_files
        # 入口 shell 与 proot 侧对齐：有 bash 就用 bash（`{}` 展开、数组等行为一致），否则回落 sh。
        # 参数用 "$@" 精确传递：旧实现把 $* 拼进 -c 字符串，带空格的参数会被重新切分。
        if [ -x "$ROOTFS/bin/bash" ]; then
            ENTRY_SH=/bin/bash
        else
            ENTRY_SH=/bin/sh
        fi
        run_in_chroot "$ENTRY_SH" -c 'cd -- "$1" 2>/dev/null || cd /root 2>/dev/null || cd /; shift; exec "$@"' chroot-run "$WORKDIR" "$@"
        rc=$?
        fixowner_quick        # 退出前把 root 写出的文件还给 app uid（可用 CHROOT_FIXOWNER_ON_EXIT=0 关掉）
        exit $rc
        ;;
    *)
        echo "usage: chroot-run.sh <rootfs_dir> {shell|exec <cmd...>|prepare|unmount}" >&2
        exit 2
        ;;
esac
