#!/system/bin/sh
# chroot runtime helper — prepare / enter / leave a glibc distro rootfs.
# Runs under `su -c` (root layer only: mount/chroot; everything else stays in
# the Shizuku layer). Single capability surface, auditable.
#
# Usage: su -c 'sh chroot-run.sh <rootfs_dir> <command...>'
#   chroot-run.sh <dir> shell          → interactive shell inside chroot
#   chroot-run.sh <dir> exec <cmd...>  → run one command inside chroot
#   chroot-run.sh <dir> prepare        → mounts only (PATH/resolv/CA fixes applied once)
#   chroot-run.sh <dir> unmount        → unmount in reverse order
#
# Runtime rules (from 2026-09-23/26 device tests):
#   1. PATH must be set explicitly inside chroot (host root PATH leaks /system/bin
#      which does not exist inside → everything "not found")
#   2. resolv.conf must be a WRITTEN FILE — bind-mounting Termux's symlink
#      mounts the link itself and DNS breaks
#   3. mount order: proc sys dev devpts tmpfs(/tmp) workspace; unmount in REVERSE order
#   4. commands run AS ROOT inside chroot (rooted devices only); /workspace
#      inside chroot = the workspace files dir (bind-mounted), same semantics
#      as the proot backend. chroot内没有 su 二进制（不 bind /system）。
#   5. **挂载常驻**：exec/shell 结束时不再自动 unmount。
#      依据（旧机实测）：单条命令的全量 mount×6 + umount×6 ≈ 630~670ms，
#      而 su 启动基线仅 ~40ms —— 多命令场景几乎全在重复挂载上白烧。
#      常驻期间的卸载由 **App 侧兜底**：关 chroot 开关 / 删除或重装工作区
#      （WorkspaceRepository.setChrootEnabled(false)、WorkspaceManager.deleteWorkspace）。
#      需要立刻清理时：`chroot-run.sh <dir> unmount`。
#   6. 并发安全：mount / unmount 段用 mkdir 原子锁（Android 的 sh 没有 flock），
#      挂载逐项幂等 —— 旧版只按 "ROOTFS 是否出现在 /proc/mounts" 判"已挂"，
#      部分残留会被误判成挂全了。

ROOTFS="$1"; shift
[ -d "$ROOTFS" ] || { echo "ERR: rootfs dir not found: $ROOTFS"; exit 2; }
[ "$(id -u)" = "0" ] || { echo "ERR: not root"; exit 2; }

MARKER="$ROOTFS/.chroot-mounted"
FIXED="$ROOTFS/.chroot-fixed"
LOCKDIR="$ROOTFS/.chroot-lock"

mounts_done() { grep -qF "$ROOTFS" /proc/mounts 2>/dev/null; }

# 精确判"某个挂载点是否已挂"
is_mounted() { grep -qF " $1 " /proc/mounts 2>/dev/null; }

all_mounted() {
    is_mounted "$ROOTFS/proc" &&
        is_mounted "$ROOTFS/sys" &&
        is_mounted "$ROOTFS/dev" &&
        is_mounted "$ROOTFS/dev/pts" &&
        is_mounted "$ROOTFS/tmp" &&
        is_mounted "$ROOTFS/workspace"
}

# mkdir 原子锁：拿不到就等；30s 仍拿不到判为陈旧锁并强制拆掉（避免死锁卡住 App）
acquire_lock() {
    i=0
    while ! mkdir "$LOCKDIR" 2>/dev/null; do
        i=$((i + 1))
        if [ "$i" -gt 300 ]; then
            echo "WARN: chroot lock stuck >30s, breaking it" >&2
            rm -rf "$LOCKDIR"
        fi
        sleep 0.1
    done
}

release_lock() { rmdir "$LOCKDIR" 2>/dev/null; }

# 幂等挂载：已挂则跳过（支持上次残留部分挂载的情况）
ensure_mount() { # $1=fstype $2=source $3=target
    if is_mounted "$3"; then return 0; fi
    mount -t "$1" "$2" "$3" || { echo "ERR: mount $3 failed" >&2; return 1; }
}

do_mounts() {
    if [ -f "$MARKER" ] && all_mounted; then echo "already mounted"; return 0; fi
    acquire_lock
    ensure_mount proc proc "$ROOTFS/proc" || { release_lock; exit 1; }
    ensure_mount sysfs sys "$ROOTFS/sys" || { release_lock; exit 1; }
    mkdir -p "$ROOTFS/dev/pts"
    if ! is_mounted "$ROOTFS/dev"; then
        mount --bind /dev "$ROOTFS/dev" || { echo "ERR: mount $ROOTFS/dev failed" >&2; release_lock; exit 1; }
    fi
    ensure_mount devpts devpts "$ROOTFS/dev/pts" || { release_lock; exit 1; }
    mkdir -p "$ROOTFS/tmp"
    ensure_mount tmpfs tmpfs "$ROOTFS/tmp" || { release_lock; exit 1; }
    # workspace files dir (sibling of the rootfs) → /workspace, same as proot
    if [ -d "$ROOTFS/../files" ]; then
        mkdir -p "$ROOTFS/workspace"
        if ! is_mounted "$ROOTFS/workspace"; then
            mount --bind "$ROOTFS/../files" "$ROOTFS/workspace" || echo "WARN: workspace bind failed" >&2
        fi
    else
        echo "WARN: workspace files dir not found, /workspace unavailable" >&2
    fi
    touch "$MARKER"
    release_lock
    echo "mounted: proc sys dev devpts tmp"
}

do_unmount() {
    acquire_lock
    # reverse order, deepest-ish last-mounted first; idempotent
    for m in "$ROOTFS/workspace" "$ROOTFS/tmp" "$ROOTFS/dev/pts" "$ROOTFS/dev" "$ROOTFS/sys" "$ROOTFS/proc"; do
        umount "$m" 2>/dev/null
    done
    rm -f "$MARKER"
    release_lock
    if mounts_done; then
        echo "WARN: some mounts remain under $ROOTFS (processes holding them?)" >&2
        grep -F "$ROOTFS" /proc/mounts >&2
        return 1
    fi
    echo "unmounted clean"
}

# one-time fixes inside rootfs (idempotent, marked)
do_fixes() {
    [ -f "$FIXED" ] && return 0
    # DNS: write file, never bind the host symlink
    mkdir -p "$ROOTFS/etc"
    printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > "$ROOTFS/etc/resolv.conf"
    # PATH hygiene marker: profile hook keeps host /system/bin out
    mkdir -p "$ROOTFS/etc/profile.d"
    printf 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n' \
        > "$ROOTFS/etc/profile.d/00-path.sh"
    touch "$FIXED"
}

case "$1" in
    prepare)
        do_mounts; do_fixes; echo "ready: $ROOTFS" ;;
    unmount)
        do_unmount ;;
    shell)
        do_mounts; do_fixes
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export HOME=/root TERM="${TERM:-xterm-256color}"
        if [ -x "$ROOTFS/bin/bash" ]; then
            chroot "$ROOTFS" /bin/bash -l
        else
            echo "WARN: $ROOTFS/bin/bash missing; falling back to /bin/sh" >&2
            chroot "$ROOTFS" /bin/sh -l
        fi
        rc=$?
        # 挂载常驻（见文件头第 5 条）：不在这里 unmount
        exit $rc ;;
    exec)
        shift
        do_mounts; do_fixes
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export HOME=/root
        chroot "$ROOTFS" /bin/sh -c "$*"
        rc=$?
        # 挂载常驻（见文件头第 5 条）：不在这里 unmount
        exit $rc ;;
    *)
        echo "usage: chroot-run.sh <rootfs_dir> {shell|exec <cmd...>|prepare|unmount}"; exit 2 ;;
esac
