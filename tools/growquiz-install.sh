#!/bin/sh
set -eu

# growquiz-install.sh: install TVQuizLock on a TV over adb, then make it reachable as a home screen.
#
# Usage:  sh tools/growquiz-install.sh [APK] [TV_IP[:PORT]]
#   APK            defaults to app/build/outputs/apk/debug/app-debug.apk
#   TV_IP[:PORT]   defaults to the one device adb already sees; port defaults to 5555
#   ADB=...        point at another adb, e.g. ADB="/d/SDK/platform-tools/adb.exe"
#
# The script never disables the stock launcher: test the new home screen first, keep the old one
# available so you can always switch back.
#
# ---------------------------------------------------------------------------------------------
# Vendor signature gate (TCL / FFalcon 雷鸟, and possibly other rebrands of the same firmware)
#
# Some TCL firmwares run a patched package installer that checks the APK signature against a
# vendor white list. A TVQuizLock release is not on that list, so a plain install fails with:
#
#     adb: failed to install ...: Failure [INSTALL_FAILED_VERIFICATION_FAILURE]
#
# and logcat shows who said no:
#
#     verifier: fetch signature info from: com.tcl.cyberui
#     verifier: signature does not match for tcl com.tvquizlock.app, will not be installed!
#
# What does NOT help (checked on a TCL tcl_mt5879_cn, Android 11):
#   - settings put global verifier_verify_adb_installs 0
#   - settings put global package_verifier_enable 0
#   - pm disable-user com.tcl.guard          (TGuard, the TV's own 卫士, is not the gate)
# The rejection comes from com.android.packageinstaller before the AOSP verifier ever runs, so no
# global switch reaches it.
#
# What works: turn com.android.packageinstaller off, install, turn it back on. The script does this
# only after a verification failure, and turns it back on unconditionally - including on Ctrl-C,
# on error, and on SIGHUP when the terminal goes away. A TV left with its package installer
# disabled cannot install anything else, so this part must not be interruptible.
# ---------------------------------------------------------------------------------------------

PACKAGE=com.tvquizlock.app
ADMIN=dev.plume.quiz.QuizDeviceAdminReceiver
VERIFIER_PKG=com.android.packageinstaller

APK=${1:-app/build/outputs/apk/release/app-release.apk}
TV_ADDRESS=${2:-}
ADB=${ADB:-adb}

RESTORE_VERIFIER=0

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Runs on every exit path once the gate has been touched. Idempotent on purpose: the install path
# calls it as soon as it is safe to, and the trap calls it again on the way out.
restore_verifier() {
  [ "$RESTORE_VERIFIER" = 1 ] || return 0
  RESTORE_VERIFIER=0
  if "$ADB" -s "$D" shell pm enable "$VERIFIER_PKG" >/dev/null 2>&1; then
    say "  ok      package installer back on"
  else
    printf 'WARNING: could not turn %s back on.\n' "$VERIFIER_PKG" >&2
    printf '         Run: adb -s %s shell pm enable %s\n' "$D" "$VERIFIER_PKG" >&2
  fi
}

trap 'restore_verifier' EXIT
trap 'restore_verifier; exit 130' INT TERM
trap 'restore_verifier; exit 129' HUP

command -v "$ADB" >/dev/null 2>&1 || die "adb not found ($ADB). Install Android platform-tools, or set ADB=/path/to/adb"
[ -f "$APK" ] || die "APK not found: $APK (run ./gradlew assembleRelease first, or pass an APK path)"

if [ -n "$TV_ADDRESS" ]; then
  case $TV_ADDRESS in
    *:*) D=$TV_ADDRESS ;;
    *) D=$TV_ADDRESS:5555 ;;
  esac
  say "Connecting to $D ..."
  "$ADB" connect "$D" >/dev/null 2>&1 || true
else
  LISTED=$("$ADB" devices | awk 'NR>1 && $2!="" {print $1}')
  [ "$(printf '%s\n' "$LISTED" | grep -c .)" -eq 1 ] \
    || die "pass the TV address (e.g. 192.168.1.20), or connect exactly one device first"
  D=$LISTED
fi

# The TV may be showing "Allow USB debugging?" - wait for the tap instead of failing straight away.
tries=0
while [ "$("$ADB" -s "$D" get-state 2>/dev/null || true)" != device ]; do
  tries=$((tries + 1))
  if [ "$tries" -gt 30 ]; then
    die "the TV did not answer. Is ADB debugging on? If it shows 'Allow USB debugging?', accept it and run this again."
  fi
  [ "$tries" -eq 1 ] && say "Waiting for the TV... accept 'Allow USB debugging?' on screen if it shows up."
  sleep 2
done

BRAND=$("$ADB" -s "$D" shell getprop ro.product.brand 2>/dev/null | tr -d '\r')
MODEL=$("$ADB" -s "$D" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
RELEASE=$("$ADB" -s "$D" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
say "Found: ${BRAND:-?} ${MODEL:-?}, Android ${RELEASE:-?}"

# try_install keeps the output of the last attempt in LOG, so the caller can tell the failure kinds
# apart instead of only reporting "it failed".
try_install() {
  LOG=$("$ADB" -s "$D" install -r "$APK" 2>&1) || true
  LOG=$(printf '%s' "$LOG" | tr -d '\r')
  case $LOG in
    *Success*) return 0 ;;
    *) return 1 ;;
  esac
}

# The TCL path: disable the patched installer, install, restore it.
install_through_vendor_gate() {
  # adb prints the progress line on stdout and the Failure line on stderr, and the two do not always
  # arrive in that order, so pick the failure code out instead of trusting the last line.
  FAILURE=$(printf '%s\n' "$LOG" | sed -n 's/.*\(INSTALL_FAILED_[A-Z_]*\).*/\1/p' | head -1)
  say "The TV's package installer rejected the APK (vendor signature white list, ${FAILURE:-INSTALL_FAILED_VERIFICATION_FAILURE})"
  "$ADB" -s "$D" shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$VERIFIER_PKG" \
    || die "rejected by the vendor gate, and $VERIFIER_PKG is not installed, so it cannot be stepped around. Install from USB instead."
  say "Stepping around it: $VERIFIER_PKG off -> install -> on"
  "$ADB" -s "$D" shell pm disable-user --user 0 "$VERIFIER_PKG" >/dev/null 2>&1 || true
  if ! "$ADB" -s "$D" shell pm list packages -d 2>/dev/null | tr -d '\r' | grep -qx "package:$VERIFIER_PKG"; then
    die "could not turn $VERIFIER_PKG off, so the vendor gate cannot be stepped around. Install from USB instead."
  fi
  RESTORE_VERIFIER=1
  if ! try_install; then
    restore_verifier
    die "install still failed with the package installer off: $LOG"
  fi
  restore_verifier
}

say "Installing $(basename "$APK") ..."
if try_install; then
  say "  ok      installed"
else
  case $LOG in
    *INSTALL_FAILED_VERIFICATION_FAILURE*) install_through_vendor_gate; say "  ok      installed" ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      die "signature clash with the copy already on the TV: it was signed with a different key (a debug build, or any release before v1.6.0). Uninstall first with: adb -s $D uninstall $PACKAGE  - this also deletes the scores and settings kept on the TV." ;;
    *INSTALL_FAILED_OLDER_SDK*)
      die "this TV is older than Android 8.0, which TVQuizLock needs. Nothing was changed." ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
      die "not enough free space on the TV. Nothing was changed." ;;
    *) die "install failed: $LOG" ;;
  esac
fi

# Read the version back from the TV instead of trusting the install output.
VERSION=$("$ADB" -s "$D" shell dumpsys package "$PACKAGE" 2>/dev/null | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1)
[ -n "$VERSION" ] || die "the TV does not report $PACKAGE as installed. Nothing else was changed."
say "  ok      $PACKAGE $VERSION is on the TV"

# Optional, but without it the launcher cannot keep its Home-button service on across a reboot.
if "$ADB" -s "$D" shell pm grant "$PACKAGE" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; then
  say "  ok      WRITE_SECURE_SETTINGS granted"
else
  say "  note    WRITE_SECURE_SETTINGS was not accepted; everything else still works"
fi

# Android 10 and newer block a background start of the quiz page unless the app holds "display over
# other apps". The system reads that as the SYSTEM_ALERT_WINDOW app op, and the permission must be
# declared for it to be settable - it is, since 1.13.1. Without this the question never comes back
# after the child opens another app.
if "$ADB" -s "$D" shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 \
   && "$ADB" -s "$D" shell appops get "$PACKAGE" SYSTEM_ALERT_WINDOW 2>/dev/null | tr -d '\r' | grep -q "allow"; then
  say "  ok      may draw over other apps (the quiz returns after a switch)"
else
  say "  note    SYSTEM_ALERT_WINDOW was not granted; the quiz stays behind another app"
fi

say "Opening TVQuizLock ..."
"$ADB" -s "$D" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true

cat <<EOF

Installed $PACKAGE $VERSION. The stock launcher was NOT disabled.

Next, on the TV:
  1. Accept the permission prompt, then pick 宝贝成长桌面 when the TV asks which home app to use.
  2. Open the phone console at the address the TV shows (http://<TV IP>:8081) and set a password.
     Age, quiz interval, categories and the question bank all live there.

If the TV has no TTS engine, questions are shown but not read out loud. On a TV with no engine at
all, install one first (for example an offline eSpeak build).

Optional, on a suitable test device only:
  adb -s $D shell dpm set-device-owner $PACKAGE/$ADMIN

Device Owner provisioning can require a fresh device, removed accounts or a factory reset.
Back up data first. It grants kiosk policy control, not root access.
EOF
