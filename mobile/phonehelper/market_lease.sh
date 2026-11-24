#!/system/bin/sh
# Independent rollback process. The caller renews the lease during its job.
umask 077
LC_ALL=C
export LC_ALL
ROOT=/data/local/tmp/codex-dual
TARGET=com.etalien.booster
MARKETS="com.hihonor.appmarket com.android.vending com.hihonor.gamecenter"
MARKET_RE='com[.]hihonor[.]appmarket/|com[.]android[.]vending/|com[.]hihonor[.]gamecenter/'
BASE=

valid_base() {
  case "$1" in
    "$ROOT"/session-*) ;;
    *) return 1 ;;
  esac
  case "${1#"$ROOT/session-"}" in
    ''|*[!a-zA-Z0-9_-]*) return 1 ;;
  esac
}

write_error() {
  printf '%s\n' "$1" >&2
  if [ -n "$BASE" ]; then
    printf '%s\n' "$1" > "$BASE.market-error.tmp" &&
      mv -f "$BASE.market-error.tmp" "$BASE.market-error"
  fi
}

fail() {
  write_error "$1"
  exit 1
}

uptime_s() {
  cut -d . -f 1 /proc/uptime
}

package_state() {
  row=$(timeout 10 dumpsys package "$1" 2>/dev/null |
    awk '/^[[:space:]]+User 0:/ && !found { print; found=1 } END { if (!found) exit 1 }') || return 1
  case "$row" in
    *' installed=true '*' enabled='*)
      state=${row#*' enabled='}
      state=${state%% *}
      case "$state" in 0|1|2|3|4) printf '%s\n' "$state";; *) return 1;; esac
      ;;
    *) return 1 ;;
  esac
}

no_install_sessions() {
  timeout 10 dumpsys package installs 2>/dev/null | awk '
    /^[[:space:]]*Active install sessions:[[:space:]]*$/ { active=1; next }
    /^[[:space:]]*Finalized install sessions:[[:space:]]*$/ { if (active) finalized=1; next }
    active && !finalized && /[^[:space:]]/ { busy=1 }
    END { exit !(active && finalized && !busy) }
  '
}

direct_install_denied() {
  ops=$(timeout 8 cmd appops get --user "$1" "$TARGET" REQUEST_INSTALL_PACKAGES 2>/dev/null) || return 1
  printf '%s\n' "$ops" | grep -qE '^REQUEST_INSTALL_PACKAGES:[[:space:]]+(ignore|deny)([;[:space:]]|$)'
}

# A journal is written before each change. Only an installed, previously
# enabled member of the fixed list may appear in it.
record_state() {
  journal="$BASE.market-journal"
  {
    if [ -f "$journal" ]; then cat "$journal" || return 1; fi
    printf '%s %s\n' "$1" "$2"
  } > "$journal.tmp" &&
    toybox fsync "$journal.tmp" &&
    mv -f "$journal.tmp" "$journal" &&
    toybox fsync "$ROOT"
}

restore_journal() (
  file="$1"
  session=${file%.market-journal}
  valid_base "$session" && [ -f "$file" ] || exit 1
  seen=" "
  while IFS=' ' read -r app original extra; do
    case "$app" in
      com.hihonor.appmarket|com.android.vending|com.hihonor.gamecenter) ;;
      *) exit 1 ;;
    esac
    case "$seen" in *" $app "*) exit 1 ;; esac
    seen="$seen$app "
    case "$original" in 0|1) ;; *) exit 1 ;; esac
    [ -z "$extra" ] || exit 1
  done < "$file"
  [ "$seen" != " " ] || exit 1

  errors=0
  changed=0
  while IFS=' ' read -r app original; do
    current=$(package_state "$app") || { errors=1; continue; }
    [ "$current" != "$original" ] || continue
    # Another state means it has changed independently. Keep its new setting.
    [ "$current" = 3 ] || { errors=1; continue; }
    case "$original" in 0) action=default-state ;; 1) action=enable ;; esac
    timeout 10 pm "$action" --user 0 "$app" >/dev/null 2>&1 || { errors=1; continue; }
    if [ "$(package_state "$app")" = "$original" ]; then changed=1; else errors=1; fi
  done < "$file"
  [ "$errors" = 0 ] || exit 1
  touch "$session.market-restored" &&
    rm -f "$file" &&
    toybox fsync "$ROOT" &&
    printf '%s\n' "$changed"
)

recover_old() {
  recovery_status=NO_PENDING
  for old in "$ROOT"/session-*.market-journal; do
    [ -e "$old" ] || [ -L "$old" ] || continue
    [ -f "$old" ] && [ ! -L "$old" ] || return 1
    old_base=${old%.market-journal}
    valid_base "$old_base" || return 1
    if [ -e "$old_base.market-ready" ] || [ -L "$old_base.market-ready" ]; then
      [ -f "$old_base.market-ready" ] && [ ! -L "$old_base.market-ready" ] || return 1
      rm -f "$old_base.market-ready" || return 1
    fi
    if ! changed=$(restore_journal "$old"); then
      printf '%s\n' '旧市场保护的原始状态未完全恢复，请先检查商店设置。' > "$old_base.market-error"
      return 1
    fi
    if [ "$changed" = 1 ]; then recovery_status=RESTORED; fi
  done
  clear_orphan_markers || return 1
  printf '%s\n' "$recovery_status"
}

# The market lock is held. Package changes only follow a committed journal.
clear_orphan_markers() {
  set --
  for marker in "$ROOT"/session-*.market-ready "$ROOT"/session-*.market-journal.tmp; do
    [ -e "$marker" ] || [ -L "$marker" ] || continue
    [ -f "$marker" ] && [ ! -L "$marker" ] || return 1
    marker_base=${marker%.market-ready}
    marker_base=${marker_base%.market-journal.tmp}
    valid_base "$marker_base" || return 1
    [ ! -e "$marker_base.market-journal" ] && [ ! -L "$marker_base.market-journal" ] || return 1
    set -- "$@" "$marker"
  done
  [ "$#" = 0 ] || { rm -f "$@" && toybox fsync "$ROOT"; }
}

finish() {
  result="$?"
  trap - EXIT
  rm -f "$BASE.market-ready" "$BASE.market-journal.tmp"
  if [ -f "$BASE.market-journal" ]; then
    if ! restore_journal "$BASE.market-journal" >/dev/null; then
      write_error '市场保护已结束，但有商店未确认恢复；已保留恢复记录。'
      exit 1
    fi
  elif ! touch "$BASE.market-restored"; then
    write_error '无法写入市场恢复标记。'
    exit 1
  fi
  exit "$result"
}

[ "$#" -ge 1 ] || exit 2
if [ "$1" = --recover ]; then
  [ "$#" = 1 ] || exit 2
  PROFILES=
else
  valid_base "$1" || exit 2
  BASE="$1"
  shift
  PROFILES=
  for user in "$@"; do
    case "$user" in ''|*[!0-9]*) exit 2 ;; esac
    [ "${#user}" -le 9 ] || exit 2
    case " $PROFILES " in *" $user "*) exit 2 ;; esac
    PROFILES="${PROFILES:+$PROFILES }$user"
  done
  [ -n "$PROFILES" ] || exit 2
fi
[ "$(stat -c '%a %u' "$ROOT" 2>/dev/null)" = '700 2000' ] ||
  fail '后台助手的手机目录权限不正确。'
exec 9>>"$ROOT/market.lock"
if ! toybox flock -n 0 <&9; then
  write_error '另一个市场保护正在运行，或设备无法加锁。'
  exit 75
fi
trap '' HUP
if ! recovered=$(recover_old); then
  fail '有尚未恢复的旧市场保护，暂不能开始。'
fi
if [ -z "$BASE" ]; then
  printf 'MARKET_RECOVERY %s\n' "$recovered"
  exit 0
fi
[ -f "$BASE.lease" ] && [ ! -f "$BASE.market-stop" ] || fail '任务未续约或已停止。'
[ ! -e "$BASE.market-ready" ] && [ ! -e "$BASE.market-restored" ] ||
  fail '这个市场会话已使用，请重新开始。'

trap finish EXIT
trap 'exit 1' INT TERM
start=$(uptime_s)
case "$start" in ''|*[!0-9]*) fail '读不到本机计时，未启动市场保护。';; esac
for user in $PROFILES; do
  direct_install_denied "$user" || fail '外星仔的直接安装权限未确认关闭。'
done
no_install_sessions || fail '系统有正在安装的应用，或无法核对安装会话。'
installed=$(timeout 10 pm list packages --user 0 2>/dev/null) || fail '无法读取机主的商店状态。'
for app in $MARKETS; do
  [ ! -f "$BASE.market-stop" ] || fail '任务已停止。'
  printf '%s\n' "$installed" | grep -Fxq "package:$app" || continue
  enabled=$(package_state "$app") || fail "无法核对商店状态：$app"
  case "$enabled" in
    2|3) continue ;; # Leave pre-disabled stores alone.
    0|1) ;;
    *) fail "无法安全停用商店：$app" ;;
  esac
  record_state "$app" "$enabled" || fail '无法保存商店原来的状态，已停止。'
  timeout 10 pm disable-user --user 0 "$app" >/dev/null 2>&1 &&
    [ "$(package_state "$app")" = 3 ] || fail "未能停用商店：$app"
done

CHECK_MARKETS="0"
for user in $PROFILES; do
  [ "$user" = 0 ] || CHECK_MARKETS="$CHECK_MARKETS $user"
done
for user in $CHECK_MARKETS; do
  for scheme in market appmarket honormarket; do
    handlers=$(timeout 8 cmd package query-activities --brief --user "$user" \
      -a android.intent.action.VIEW -d "$scheme://details?id=$TARGET" 2>/dev/null) ||
      fail '无法核对市场链接的处理者。'
    if printf '%s\n' "$handlers" | grep -qE "$MARKET_RE"; then
      fail '广告仍能打开可代装的商店，已停止。'
    fi
  done
done
no_install_sessions || fail '系统有正在安装的应用，或无法核对安装会话。'
[ ! -f "$BASE.market-stop" ] || fail '任务已停止。'
stamp=$(stat -c %Y "$BASE.lease" 2>/dev/null) || fail '任务租约已失效。'
renewed=$(uptime_s)
touch "$BASE.market-ready" || fail '无法写入市场保护状态。'
while [ ! -f "$BASE.market-stop" ]; do
  now=$(uptime_s)
  case "$now" in ''|*[!0-9]*) fail '读不到本机计时。';; esac
  next=$(stat -c %Y "$BASE.lease" 2>/dev/null) || fail '任务租约已失效。'
  if [ "$next" != "$stamp" ]; then
    stamp="$next"
    renewed="$now"
  fi
  if [ "$((now - renewed))" -ge 120 ] || [ "$((now - start))" -ge 5700 ]; then
    fail '后台任务超时，已结束市场保护。'
  fi
  sleep 2
done
