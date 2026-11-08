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
[ "$(id -u)" = "0" ] || { echo "ERR: not root" >&2; exit 2; }

CHROOT_PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
WORKDIR="${CHROOT_WORKDIR:-/workspace}"

HOST_SH="/system/bin/sh"
[ -x "$HOST_SH" ] || HOST_SH="/bin/sh"

# Enter an isolated mount namespace so all mounts self-destruct on process exit.
# Toybox 0.8.9 `unshare` does not support `--propagation`, so we use `unshare -m`
# followed by `mount --make-rprivate /` inside the new namespace.
enter_private_ns() {
    if [ "${_CHROOT_IN_NS:-0}" != "1" ]; then
        if command -v unshare >/dev/null 2>&1; then
            export _CHROOT_IN_NS=1
            exec unshare -m "$HOST_SH" "$0" "$ROOTFS" "$@"
        fi
    fi
    mount --make-rprivate / 2>/dev/null || mount --make-private / 2>/dev/null || true
}

do_unmount_quiet() {
    umount -R "$ROOTFS" 2>/dev/null || true
    for m in \
        "$ROOTFS/workspace" \
        "$ROOTFS/skills" \
        "$ROOTFS/tool_outputs" \
        "$ROOTFS/tmp" \
        "$ROOTFS/dev/pts" \
        "$ROOTFS/dev" \
        "$ROOTFS/sys" \
        "$ROOTFS/proc"; do
        umount "$m" 2>/dev/null || umount -l "$m" 2>/dev/null || true
    done
    rm -rf "$ROOTFS"/.chroot-mounted "$ROOTFS"/.chroot-fixed "$ROOTFS"/.chroot-lock 2>/dev/null || true
}

do_unmount() {
    do_unmount_quiet
    rm -rf "$ROOTFS"/.chroot-* 2>/dev/null || true
    echo "unmounted clean"
    return 0
}

do_mounts() {
    mkdir -p "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev/pts" "$ROOTFS/tmp" "$ROOTFS/workspace"
    mount -t proc proc "$ROOTFS/proc" || { echo "ERR: mount $ROOTFS/proc failed" >&2; return 1; }
    mount -t sysfs sysfs "$ROOTFS/sys" 2>/dev/null \
        || mount -t sysfs sys "$ROOTFS/sys" \
        || { echo "ERR: mount $ROOTFS/sys failed" >&2; return 1; }
    mount --bind /dev "$ROOTFS/dev" || { echo "ERR: mount $ROOTFS/dev failed" >&2; return 1; }
    mkdir -p "$ROOTFS/dev/pts"
    mount -t devpts devpts "$ROOTFS/dev/pts" 2>/dev/null \
        || mount --bind /dev/pts "$ROOTFS/dev/pts" 2>/dev/null \
        || { echo "ERR: mount $ROOTFS/dev/pts failed" >&2; return 1; }
    mount -t tmpfs tmpfs "$ROOTFS/tmp" || { echo "ERR: mount $ROOTFS/tmp failed" >&2; return 1; }
    if [ -d "$ROOTFS/../files" ]; then
        mount --bind "$ROOTFS/../files" "$ROOTFS/workspace" || {
            echo "ERR: mount $ROOTFS/workspace failed" >&2
            return 1
        }
    fi
    return 0
}

# Non-destructive DNS & PATH defaults: only populate if missing so existing rootfs
# files keep their exact sha256 across proot <-> root chroot switches.
do_fixes() {
    mkdir -p "$ROOTFS/etc"
    if [ ! -s "$ROOTFS/etc/resolv.conf" ] && [ ! -L "$ROOTFS/etc/resolv.conf" ]; then
        printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > "$ROOTFS/etc/resolv.conf"
    fi
    mkdir -p "$ROOTFS/etc/profile.d"
    if [ ! -s "$ROOTFS/etc/profile.d/00-path.sh" ]; then
        printf 'export PATH=%s\n' "$CHROOT_PATH" > "$ROOTFS/etc/profile.d/00-path.sh"
    fi
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
        do_fixes
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
        n=0
        for area in "$ROOTFS/root" "$ROOTFS/workspace"; do
            [ -d "$area" ] || continue
            before=$(find "$area" -user 0 2>/dev/null | wc -l)
            if [ "$before" -gt 0 ]; then
                find "$area" -user 0 -exec chown "$appuid:$appuid" {} + 2>/dev/null
            fi
            after=$(find "$area" -user 0 2>/dev/null | wc -l)
            n=$((n + before - after))
            [ "$after" -gt 0 ] && echo "WARN: $area still has $after zero-owner files" >&2
        done
        echo "fixowner: fixed zero-owner files = $n (uid=$appuid)" >&2
        ;;
    shell)
        enter_private_ns shell
        trap 'do_unmount_quiet' EXIT INT TERM
        do_fixes
        do_mounts || exit 1
        if [ -x "$ROOTFS/bin/bash" ]; then
            run_in_chroot /bin/bash -l
        else
            run_in_chroot /bin/sh -l
        fi
        rc=$?
        exit $rc
        ;;
    exec)
        shift
        enter_private_ns exec "$@"
        trap 'do_unmount_quiet' EXIT INT TERM
        do_fixes
        do_mounts || exit 1
        run_in_chroot /bin/sh -c "cd -- \"$WORKDIR\" 2>/dev/null || cd /root 2>/dev/null || cd /; $*"
        rc=$?
        exit $rc
        ;;
    *)
        echo "usage: chroot-run.sh <rootfs_dir> {shell|exec <cmd...>|prepare|unmount}" >&2
        exit 2
        ;;
esac
