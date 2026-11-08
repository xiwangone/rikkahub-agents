#!/system/bin/sh
# chroot capability probe — run under `su -c` on a rooted Android device.
# Reports S1 capability fields (Uid/CapEff/Seccomp/SELinux/chroot/unshare) and
# whether the device can host the Branch A chroot backend without touching rootfs.
# Exit code 0 = all mandatory checks passed.

v=0; [ "$1" = "-v" ] && v=1
pass=0; fail=0; warn=0
ok()   { echo "PASS: $1"; pass=$((pass+1)); }
bad()  { echo "FAIL: $1"; fail=$((fail+1)); }
warn_(){ echo "WARN: $1"; warn=$((warn+1)); }
info() { [ "$v" = 1 ] && echo "INFO: $1"; return 0; }

# 0. must be root + S1 status snapshot
[ "$(id -u)" = "0" ] || { echo "FAIL: not root (run via su)"; echo "branch: C"; echo "summary: pass=0 fail=1 warn=0"; exit 2; }
if [ -r /proc/self/status ]; then
    grep -E '^(Uid|Gid|CapEff|CapBnd|Seccomp|NoNewPrivs):' /proc/self/status 2>/dev/null || true
fi
selinux_domain="$(cat /proc/self/attr/current 2>/dev/null | tr -d '\0\n' || id -Z 2>/dev/null || echo 'unknown')"
echo "SELinux domain : ${selinux_domain:-unknown}"

# 1. unshare -m private mount namespace capability (Branch A prerequisite)
if command -v unshare >/dev/null 2>&1; then
    if unshare -m /system/bin/true 2>/dev/null || unshare -m /bin/true 2>/dev/null; then
        ok "unshare -m (private mount namespace)"
    else
        warn_ "unshare -m failed (will rely on trap unmount fallback)"
    fi
else
    warn_ "unshare binary not found"
fi

# 2. mount / proc availability
mkdir -p /data/local/tmp/.probe_m
if mount -t proc proc /data/local/tmp/.probe_m 2>/dev/null; then
    umount /data/local/tmp/.probe_m && ok "mount -t proc" || bad "mount -t proc (umount failed)"
else
    if mount --bind /data /data/local/tmp/.probe_m 2>/dev/null; then
        umount /data/local/tmp/.probe_m && ok "mount --bind (proc busy = already mounted)" || bad "mount --bind (umount failed)"
    else
        bad "mount capability (both proc and bind failed)"
    fi
fi
rmdir /data/local/tmp/.probe_m 2>/dev/null

# 3. kernel page size (16KB-page devices: self-compiled native binaries must match)
ps=$(getconf PAGESIZE 2>/dev/null || echo unknown)
info "page size      : $ps"
[ "$ps" = "16384" ] && warn_ "16KB pages: self-built native binaries need page-size adaptation"

# 4. phantom process killer (Android 12+; kills long-lived child processes)
if settings get global settings_enable_monitor_phantom_procs >/dev/null 2>&1; then
    pp=$(settings get global settings_enable_monitor_phantom_procs 2>/dev/null)
    info "phantom procs  : $pp"
    [ "$pp" = "null" ] || [ "$pp" = "true" ] && warn_ "phantom process killer active: long chroot sessions may be killed (Android 12+)"
else
    info "phantom procs  : settings cmd unavailable (pre-12 or restricted)"
fi

# 5. chroot(2) actually works? (check / first as in S1, then empty probe dir)
chroot_ok=0
if chroot / /system/bin/true 2>/dev/null || chroot / /bin/true 2>/dev/null; then
    ok "chroot(2) syscall"
    chroot_ok=1
elif mkdir -p /data/local/tmp/.probe_cr 2>/dev/null; then
    err=$(chroot /data/local/tmp/.probe_cr /bin/true 2>&1)
    case "$err" in
        *Permission*|*denied*|*not\ implemented*|*ENOSYS*) bad "chroot(2): $err" ;;
        *) ok "chroot(2) syscall (binary not found inside = expected for empty dir)"; chroot_ok=1 ;;
    esac
    rmdir /data/local/tmp/.probe_cr 2>/dev/null
fi

# 6. devpts availability (needed for interactive terminals inside chroot)
if [ -d /dev/pts ] && grep -q devpts /proc/mounts 2>/dev/null; then
    ok "devpts present on host"
else
    warn_ "devpts not found — interactive PTY inside chroot may need manual devpts mount"
fi

if [ "$fail" = 0 ] && [ "$chroot_ok" = 1 ]; then
    echo "branch: A"
elif [ "$chroot_ok" = 0 ]; then
    echo "branch: B"
else
    echo "branch: C"
fi
echo "----------------------------------------"
echo "summary: pass=$pass fail=$fail warn=$warn"
[ "$fail" = 0 ]
