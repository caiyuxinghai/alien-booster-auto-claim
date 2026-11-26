#!/system/bin/sh
set -eu
umask 077

[ "$#" = 1 ] || exit 2
id=$1
case "$id" in ''|*[!0-9a-f]*) exit 2;; esac
[ "${#id}" = 32 ] || exit 2

d=/data/local/tmp/codex-dual
stage="$d/mobile-$id"
[ -d "$d" ] && [ ! -L "$d" ] && [ "$(stat -c '%a %u' "$d")" = '700 2000' ] || exit 2
[ -d "$stage" ] && [ ! -L "$stage" ] && [ "$(stat -c '%a %u' "$stage")" = '700 2000' ] || exit 2
for name in dual-helper.jar market_lease.sh supervise.sh; do
  [ -f "$stage/$name" ] && [ ! -L "$stage/$name" ] || exit 2
done
cd "$d"
exec 9>>setup.lock
toybox flock -n 0 <&9 || { echo FAIL_BUSY; exit 1; }

helper_pids() (
  set -o pipefail
  ps -A -o USER,PID,ARGS | awk '$1 == "shell" && $2 ~ /^[0-9]+$/ &&
    $3 ~ /(^|\/)app_process(32|64)?$/ && $4 == "/" && $5 == "DualDaemon" && NF == 5 {print $2}'
)
alive() { [ -n "$(helper_pids)" ]; }

run-as com.alienbooster.claimer cat files/dual.token >"$stage/token" 2>/dev/null || {
  echo FAIL_APP_AUTH
  exit 1
}
[ "$(wc -c <"$stage/token")" -eq 64 ] && grep -Eq '^[0-9a-f]{64}$' "$stage/token" || {
  echo FAIL_APP_AUTH
  exit 1
}

old=$(helper_pids) || exit 1
if [ -n "$old" ]; then
  case "$old" in *[!0-9]*) echo FAIL_BUSY; exit 1;; esac
  exec 8>>market.lock
  toybox flock -n 0 <&8 || { echo FAIL_BUSY; exit 1; }
  grep -q '^DUAL_DAEMON_READY$' daemon.log || { echo FAIL_BUSY; exit 1; }
  state=$(awk '$1 == "DUAL_STATUS" {s=$2} END {print s}' daemon.log)
  case "$state" in ''|IDLE|DONE|STOPPED|ERROR|RECOVERED|RECOVERY_FAILED) ;; *) echo FAIL_BUSY; exit 1;; esac
  for marker in session-*.market-ready session-*.market-journal session-*.market-journal.tmp; do
    [ ! -e "$marker" ] && [ ! -L "$marker" ] || { echo FAIL_BUSY; exit 1; }
  done
  [ "$(helper_pids)" = "$old" ] || { echo FAIL_BUSY; exit 1; }
  printf '%s\n' "invalid-$id" >"$stage/generation"
  mv -f "$stage/generation" generation
  kill -TERM "$old"
  i=0
  while kill -0 "$old" 2>/dev/null; do
    i=$((i+1))
    [ "$i" -lt 25 ] || { echo FAIL_BUSY; exit 1; }
    sleep 0.2
  done
  [ -z "$(helper_pids)" ] || { echo FAIL_BUSY; exit 1; }
  exec 8>&-
fi

chmod 400 "$stage/dual-helper.jar"
chmod 600 "$stage/market_lease.sh" "$stage/supervise.sh" "$stage/token"
for name in dual-helper.jar market_lease.sh supervise.sh token; do
  mv -f "$stage/$name" "$name"
done
printf '%s\n' "$id" >"$stage/generation"
mv -f "$stage/generation" generation
if [ -s daemon.log ]; then mv -f daemon.log daemon.previous.log; fi
setsid /system/bin/sh "$d/supervise.sh" "$id" 9>&- </dev/null >"$d/supervisor.log" 2>&1 &
exec 9>&-
i=0
while [ "$i" -lt 45 ]; do
  [ "$(cat generation 2>/dev/null)" = "$id" ] || exit 1
  if grep -q '^DUAL_DAEMON_READY$' daemon.log && alive; then echo READY; exit 0; fi
  i=$((i+1))
  sleep 0.4
done
echo FAIL_START
exit 1
