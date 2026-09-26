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
#   3. mount order: proc sys dev devpts tmpfs(/tmp); unmount in REVERSE order
#   4. never leave mounts behind: unmount is idempotent and best-effort

ROOTFS="$1"; shift
[ -d "$ROOTFS" ] || { echo "ERR: rootfs dir not found: $ROOTFS"; exit 2; }
[ "$(id -u)" = "0" ] || { echo "ERR: not root"; exit 2; }

MARKER="$ROOTFS/.chroot-mounted"
FIXED="$ROOTFS/.chroot-fixed"

mounts_done() { grep -qF "$ROOTFS" /proc/mounts 2>/dev/null; }

do_mounts() {
    if [ -f "$MARKER" ]; then echo "already mounted"; return 0; fi
    mount -t proc proc "$ROOTFS/proc"       || { echo "ERR: proc"; exit 1; }
    mount -t sysfs sys "$ROOTFS/sys"        || { echo "ERR: sys"; exit 1; }
    mount --bind /dev "$ROOTFS/dev"         || { echo "ERR: dev"; exit 1; }
    mkdir -p "$ROOTFS/dev/pts"
    mount -t devpts devpts "$ROOTFS/dev/pts" || { echo "ERR: devpts"; exit 1; }
    mkdir -p "$ROOTFS/tmp"
    mount -t tmpfs tmpfs "$ROOTFS/tmp"      || { echo "ERR: tmpfs"; exit 1; }
    touch "$MARKER"
    echo "mounted: proc sys dev devpts tmp"
}

do_unmount() {
    # reverse order, deepest-ish last-mounted first; idempotent
    for m in "$ROOTFS/tmp" "$ROOTFS/dev/pts" "$ROOTFS/dev" "$ROOTFS/sys" "$ROOTFS/proc"; do
        umount "$m" 2>/dev/null
    done
    rm -f "$MARKER"
    if mounts_done; then
        echo "WARN: some mounts remain under $ROOTFS (processes holding them?)"
        grep -F "$ROOTFS" /proc/mounts
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
        chroot "$ROOTFS" /bin/bash -l 2>/dev/null || chroot "$ROOTFS" /bin/sh -l
        rc=$?; do_unmount >/dev/null; exit $rc ;;
    exec)
        shift
        do_mounts; do_fixes
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export HOME=/root
        chroot "$ROOTFS" /bin/sh -c "$*"
        rc=$?; do_unmount >/dev/null; exit $rc ;;
    *)
        echo "usage: chroot-run.sh <rootfs_dir> {shell|exec <cmd...>|prepare|unmount}"; exit 2 ;;
esac
