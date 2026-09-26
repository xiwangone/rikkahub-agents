#!/system/bin/sh
# chroot capability probe — run under `su -c` on a rooted Android device.
# Reports whether the device can host the chroot backend, without touching
# any distro rootfs. Exit code 0 = all mandatory checks passed.
#
# Usage: su -c 'sh /path/to/chroot-probe.sh' [-v]
#   -v: also print optional/info lines

v=0; [ "$1" = "-v" ] && v=1
pass=0; fail=0; warn=0
ok()   { echo "PASS: $1"; pass=$((pass+1)); }
bad()  { echo "FAIL: $1"; fail=$((fail+1)); }
warn_(){ echo "WARN: $1"; warn=$((warn+1)); }
info() { [ "$v" = 1 ] && echo "INFO: $1"; return 0; }

# 0. must be root
[ "$(id -u)" = "0" ] || { echo "FAIL: not root (run via su)"; exit 2; }
echo "SELinux domain : $(id -Z 2>/dev/null || echo 'unknown (id -Z unsupported)')"

# 1. mount / proc availability
mkdir -p /data/local/tmp/.probe_m
if mount -t proc proc /data/local/tmp/.probe_m 2>/dev/null; then
    umount /data/local/tmp/.probe_m && ok "mount -t proc" || bad "mount -t proc (umount failed)"
else
    # /proc cannot be mounted twice; bind a known dir instead as mount-capability probe
    if mount --bind /data /data/local/tmp/.probe_m 2>/dev/null; then
        umount /data/local/tmp/.probe_m && ok "mount --bind (proc busy = already mounted)" || bad "mount --bind (umount failed)"
    else
        bad "mount capability (both proc and bind failed)"
    fi
fi
rmdir /data/local/tmp/.probe_m 2>/dev/null

# 2. mount namespace: is our mount visible to a fresh process (cross-ns check)?
mkdir -p /data/local/tmp/.probe_ns
touch /data/local/tmp/.probe_ns/marker
if mount --bind /data/local/tmp/.probe_ns /data/local/tmp/.probe_ns 2>/dev/null; then
    # marker file must still be visible through the bind; then check from a subshell
    if [ -f /data/local/tmp/.probe_ns/marker ] && su -c "test -f /data/local/tmp/.probe_ns/marker" 2>/dev/null; then
        ok "bind mount visible across su sessions (shared mount ns)"
    else
        warn_ "bind mount NOT visible to fresh su session (isolated ns — mounts done here will be invisible to the app)"
    fi
    umount /data/local/tmp/.probe_ns 2>/dev/null
else
    warn_ "bind mount self-test failed"
fi
rm -rf /data/local/tmp/.probe_ns

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

# 5. chroot(2) actually works? (needs CAP_SYS_CHROOT; try a no-op chroot into a dir without /bin)
if mkdir -p /data/local/tmp/.probe_cr 2>/dev/null; then
    if chroot /data/local/tmp/.probe_cr /system/bin/true 2>/dev/null \
       || chroot /data/local/tmp/.probe_cr /bin/true 2>/dev/null; then
        ok "chroot(2) syscall"
    else
        # chroot into an empty dir with a statically-known binary is unreliable;
        # treat "permission denied" as failure, anything else as pass-with-note
        err=$(chroot /data/local/tmp/.probe_cr /bin/true 2>&1)
        case "$err" in
            *Permission*|*denied*) bad "chroot(2): $err" ;;
            *) ok "chroot(2) syscall (binary not found inside = expected for empty dir)" ;;
        esac
    fi
    rmdir /data/local/tmp/.probe_cr 2>/dev/null
fi

# 6. devpts availability (needed for interactive terminals inside chroot)
if [ -d /dev/pts ] && grep -q devpts /proc/mounts 2>/dev/null; then
    ok "devpts present on host"
else
    warn_ "devpts not found — interactive PTY inside chroot may need manual devpts mount"
fi

echo "----------------------------------------"
echo "summary: pass=$pass fail=$fail warn=$warn"
[ "$fail" = 0 ]
