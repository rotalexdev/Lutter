#!/usr/bin/env bash
# Does the package a human downloads actually run?
#
#   scripts/probe-desktop-package.sh <app-name> <app-dir> <probe-dir>
#
#   scripts/probe-desktop-package.sh LutterEditor desktopApp editor-probe
#
# `<app-name>` is jpackage's `packageName` and therefore the launcher's file name;
# `<app-dir>` is the Gradle module the image was built from; `<probe-dir>` is where the two logs
# land, relative to the workspace because `$RUNNER_TEMP` on a Windows runner is a path full of
# backslashes that bash reads as escapes.
#
# Extracted from `.github/workflows/preview-artifacts.yml`, which grew this probe one report at a
# time: "Failed to launch JVM" from a launcher nobody had ever executed, a Windows job that sat
# twelve minutes on a launcher that had succeeded, and a structure report that listed a missing
# file on Linux because the two layouts are not the same shape. All of that knowledge is here so
# that the second application to be packaged does not have to rediscover it.
#
# Exit codes: 0 when the launcher started (or when the only failure is "no display", which a GUI
# runner cannot avoid), 1 when the package is missing or the launcher cannot create a VM.
set -uo pipefail

app="${1:?usage: probe-desktop-package.sh <app-name> <app-dir> <probe-dir>}"
dir="${2:?usage: probe-desktop-package.sh <app-name> <app-dir> <probe-dir>}"
probe="${3:?usage: probe-desktop-package.sh <app-name> <app-dir> <probe-dir>}"

root="$dir/build/compose/binaries/main/app/$app"
mkdir -p "$probe"

# ---------------------------------------------------------------------------
# Structure. A report, not a gate: several commands below are expected to fail,
# because "is this file here?" is the question being asked.
# ---------------------------------------------------------------------------
{
  echo "== app-image root =="
  echo "$root"
  echo
  echo "== top level =="
  ls -la "$root" 2>&1
  echo
  echo "== the VM itself =="
  # The two layouts are NOT the same shape. Windows puts the runtime at `runtime/` and the jars at
  # `app/`; Linux puts both under `lib/`. A single `ls` for both legs reports a missing file on
  # one of them and makes a working package look broken.
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      ls -la "$root/runtime/bin/server/jvm.dll" 2>&1
      ls -la "$root/runtime/bin/java.dll" 2>&1
      ;;
    *)
      ls -la "$root/lib/runtime/lib/server/libjvm.so" 2>&1
      ls -la "$root/lib/runtime/lib/libjli.so" 2>&1
      ;;
  esac
  echo
  echo "== the launcher's configuration =="
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) cfg="$root/app/$app.cfg" ;;
    *) cfg="$root/lib/app/$app.cfg" ;;
  esac
  ls -la "$cfg" 2>&1
  echo "--- first lines ---"
  head -3 "$cfg" 2>&1
  echo
  echo "== Skiko's native library =="
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ls -la "$root/app/"*skiko* 2>&1 ;;
    *) ls -la "$root/lib/app/"*skiko* 2>&1 ;;
  esac
  echo
  echo "== how many jars, and how big =="
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ls "$root/app/"*.jar 2>/dev/null | wc -l ;;
    *) ls "$root/lib/app/"*.jar 2>/dev/null | wc -l ;;
  esac
  du -sh "$root" 2>&1
} > "$probe/structure-probe.txt" 2>&1
cat "$probe/structure-probe.txt"

# ---------------------------------------------------------------------------
# Launch. The gate.
#
# There is no `java` executable inside a jlink image, so "can this runtime create a VM?" cannot be
# asked by running its own java. The launcher is a better probe anyway: the jpackage launcher
# calls `JNI_CreateJavaVM` BEFORE any application code runs, so a failure to start the JVM is
# reported before AWT is ever touched. That is what makes the distinction this repository could
# not previously make — "the package is broken" against "the runner has no display" — decidable
# on a headless machine.
#
# Bounded, because a launcher that succeeds hangs: it creates the VM, initialises AWT, opens a
# window and waits for a user who is not there. Background, poll, kill. `timeout(1)` is not
# guaranteed on the Windows Git Bash image, and a probe that only works on one leg is not a probe.
# ---------------------------------------------------------------------------
log="$probe/launch-probe.txt"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) launcher="$root/$app.exe" ;;
  *) launcher="$root/bin/$app" ;;
esac

if [ ! -e "$launcher" ]; then
  echo "::error::no launcher at '$launcher' - the package did not build"
  exit 1
fi

"$launcher" > "$log" 2>&1 &
pid=$!

waited=0
while kill -0 "$pid" 2>/dev/null; do
  if [ "$waited" -ge 25 ]; then
    echo "== the launcher was still running after ${waited}s; it started and is waiting for a window ==" >> "$log"
    kill "$pid" 2>/dev/null
    sleep 1
    kill -9 "$pid" 2>/dev/null
    break
  fi
  sleep 1
  waited=$((waited + 1))
done
wait "$pid" 2>/dev/null
code=$?
echo "exit=$code after ${waited}s" >> "$log"
cat "$log"

if grep -qiE "failed to launch jvm|cannot find the java|unable to create vm|no jvm" "$log"; then
  echo "::error::$app could not start a JVM - the package is broken, not merely headless"
  exit 1
fi

# Everything else is reported, not fatal: a GUI runner has no interactive desktop, so the
# application is expected to fail *after* the VM started, and getting that far is the evidence.
echo "::notice::$app started; whatever it printed next is the application, not the packaging"
