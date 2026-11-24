#!/system/bin/sh
# Restart the dashboard listener after a crash or app-requested restart. Jobs are started by the app.
set -u
umask 077
d=/data/local/tmp/codex-dual
cd "$d" || exit 1
[ "$#" -eq 1 ] || exit 1
gen="$1"
failures=0
echo "SUPERVISOR_START $gen"
while [ "$(cat generation 2>/dev/null)" = "$gen" ]; do
    # Serialize launch with provisioning and recheck the generation under that lock.
    exec 9>>setup.lock
    toybox flock -x 0 <&9 || exit 1
    [ "$(cat generation 2>/dev/null)" = "$gen" ] || exit 0
    start=$(cut -d . -f 1 /proc/uptime)
    if [ -s daemon.log ]; then mv -f daemon.log daemon.previous.log; fi
    CODEX_DUAL_SUPERVISOR_PID="$$" CODEX_DUAL_GENERATION="$gen" \
      CLASSPATH="$d/dual-helper.jar" app_process / DualDaemon 9>&- </dev/null >daemon.log 2>&1 &
    child=$!
    exec 9>&-
    wait "$child"
    result=$?
    [ "$(cat generation 2>/dev/null)" = "$gen" ] || { echo "SUPERVISOR_GENERATION_CHANGED"; exit 0; }
    echo "SUPERVISOR_CHILD_EXIT $result"
    if [ "$result" -eq 75 ]; then
        echo "SUPERVISOR_REQUESTED_RESTART"
    else
        now=$(cut -d . -f 1 /proc/uptime)
        if [ "$((now-start))" -ge 60 ]; then failures=0; else failures=$((failures+1)); fi
        [ "$failures" -lt 3 ] || { echo "SUPERVISOR_EXIT_LIMIT"; exit 1; }
    fi
    echo "SUPERVISOR_RESTART"
    sleep 3
done
