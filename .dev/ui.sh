#!/bin/bash
# Shared terminal UI for the local dev scripts: banner, animated status line, live logs, log file.

# On Windows "bash" often means WSL. Linux pnpm would wipe node_modules in a checkout on a Windows drive, so hand over
# to the Windows side there. A checkout inside the WSL file system is a normal Linux checkout.
if grep -qi microsoft /proc/version 2> /dev/null && [[ "$(pwd -P)" == /mnt/* ]]; then
  printf 'WSL erkannt, starte unter Windows ...\n'
  exec /mnt/c/Windows/System32/cmd.exe /c "$(wslpath -w "$0").cmd" "$@"
fi

shopt -s extglob

C_RED=$'\e[38;5;196m'; C_DARK=$'\e[38;5;88m'; C_GLOW=$'\e[38;5;209m'; C_GREEN=$'\e[38;5;77m'
C_YELLOW=$'\e[38;5;221m'; C_DIM=$'\e[38;5;244m'; C_BOLD=$'\e[1m'; C_OFF=$'\e[0m'; ESC_CLEAR=$'\r\e[K'

DEV_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$DEV_ROOT/.dev/logs"
mkdir -p "$LOG_DIR"
if [[ -d /mingw64/bin ]]; then export PATH="/usr/bin:/mingw64/bin:$PATH"; fi
export PATH="$HOME/.cargo/bin:$PATH"

# Settings of this machine, not in Git: TRS_TEST_HOME, TRS_BUILD_LOCK, TRS_WORKTREES_DIR, TRS_STUDIO_DIR.
# shellcheck disable=SC1091
[[ -f "$DEV_ROOT/.dev/local.sh" ]] && source "$DEV_ROOT/.dev/local.sh"

ui_path() { # path in the form the native tools of this system expect
  if command -v cygpath > /dev/null; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

ui_running() { # ui_running <name...> : is one of these programs running?
  local name
  for name in "$@"; do
    if command -v tasklist > /dev/null; then
      tasklist 2> /dev/null | grep -qi "^$name\.exe" && return 0
    else
      pgrep -x "$name" > /dev/null && return 0
    fi
  done
  return 1
}

UI_PHASE="Startet"; UI_DETAIL=""; UI_PERCENT=-1; UI_TICK=0; UI_PID=""; UI_LOCK=0; UI_FAILS=()

ui_banner() { # ui_banner <title> <subtitle>
  local rows=(
    "  ████████╗██████╗ ███████╗"
    "  ╚══██╔══╝██╔══██╗██╔════╝"
    "     ██║   ██████╔╝███████╗"
    "     ██║   ██╔══██╗╚════██║"
    "     ██║   ██║  ██║███████║"
    "     ╚═╝   ╚═╝  ╚═╝╚══════╝"
  )
  local colors=(196 196 160 160 124 88) i
  printf '\n'
  for i in "${!rows[@]}"; do
    printf '\e[38;5;%sm%s%s\n' "${colors[$i]}" "${rows[$i]}" "$C_OFF"
    sleep 0.04
  done
  printf '\n  %s%s%s  %s%s%s\n\n' "$C_BOLD" "$1" "$C_OFF" "$C_DIM" "$2" "$C_OFF"
}

ui_step() { printf '%s  %s●%s %s\n' "$ESC_CLEAR" "$C_RED" "$C_OFF" "$1"; }
ui_ok()   { printf '%s  %s✓%s %s\n' "$ESC_CLEAR" "$C_GREEN" "$C_OFF" "$1"; }
ui_warn() { printf '%s  %s!%s %s\n' "$ESC_CLEAR" "$C_YELLOW" "$C_OFF" "$1"; }
ui_fail() { printf '%s  %s✗%s %s\n' "$ESC_CLEAR" "$C_RED" "$C_OFF" "$1"; }

ui_time() { printf '%02d:%02d' $(($1 / 60)) $(($1 % 60)); }

# Status line: a redstone signal runs along the dust, the torch flickers.
ui_draw() {
  local width=18 i track="" torch pos
  pos=$((UI_TICK % width))
  for ((i = 0; i < width; i++)); do
    if ((i == pos)); then track+="${C_GLOW}━"
    elif (((i + 1) % width == pos || (i + 2) % width == pos)); then track+="${C_RED}━"
    else track+="${C_DARK}─"; fi
  done
  if ((UI_TICK % 6 < 4)); then torch="${C_RED}✦"; else torch="${C_DARK}✦"; fi
  local percent=""
  ((UI_PERCENT >= 0)) && percent=" ${C_BOLD}${UI_PERCENT} %${C_OFF}"
  printf '%s  %s %s%s %s%s%s%s  %s%s%s %s' "$ESC_CLEAR" "$torch" "$track" "$C_OFF" "$C_BOLD" "$UI_PHASE" "$C_OFF" "$percent" \
    "$C_DIM" "$(ui_time $SECONDS)" "$C_OFF" "${C_DIM}${UI_DETAIL:0:60}${C_OFF}"
  UI_TICK=$((UI_TICK + 1))
}

ui_line() { # prints a log line above the status line, coloured by content
  local raw="${1%$'\r'}" plain colour=""
  raw="${raw##*$'\r'}"
  plain="${raw//$'\e['*([0-9;?])[a-zA-Z]/}"
  printf '%s\n' "$plain" >> "$UI_LOG"
  [[ -z "${plain//[[:space:]]/}" ]] && return
  case "${plain,,}" in
    *error*|*fehler*|*panic*|*exception*|*failed*) colour="$C_RED"; UI_FAILS+=("$plain"); ((${#UI_FAILS[@]} > 12)) && UI_FAILS=("${UI_FAILS[@]:1}") ;;
    *warn*) colour="$C_YELLOW" ;;
    *finished*|*successful*|*ready*|*running*) colour="$C_GREEN" ;;
    *) colour="$C_DIM" ;;
  esac
  printf '%s  %s%s%s\n' "$ESC_CLEAR" "$colour" "$plain" "$C_OFF"
  declare -F on_line > /dev/null && on_line "$plain"
}

ui_kill() { # ends only the process tree this script started
  if [[ -n "$UI_PID" && -r "/proc/$UI_PID/winpid" ]]; then
    taskkill //PID "$(cat "/proc/$UI_PID/winpid")" //T //F > /dev/null 2>&1
  elif [[ -n "$UI_PID" ]]; then
    pkill -TERM -P "$UI_PID" 2> /dev/null
    kill -TERM "$UI_PID" 2> /dev/null
  fi
  UI_PID=""
}

lock_release() {
  ((UI_LOCK)) && [[ -n "$LOCK_FILE" ]] && grep -q '^start-client' "$LOCK_FILE" 2> /dev/null && rm -f "$LOCK_FILE"
  UI_LOCK=0
}

ui_cleanup() { # runs on every exit, also after an error in the script itself
  ui_kill
  lock_release
  printf '\e[?25h'
}
trap ui_cleanup EXIT

ui_stop() {
  printf '%s\n' "$ESC_CLEAR"
  ui_warn "Abgebrochen, räume auf …"
  exit 130
}

ui_run() { # ui_run <logname> <command...> : streams the command, animates, returns its exit code
  UI_LOG="$LOG_DIR/$1-$(date +%Y%m%d-%H%M%S).log"; shift
  trap ui_stop INT TERM
  printf '\e[?25l'
  exec 3< <(exec "$@" 2>&1)
  UI_PID=$!
  local buf="" part rc
  while :; do
    if IFS= read -r -t 0.12 -u 3 part; then
      ui_line "$buf$part"; buf=""
    else
      rc=$?
      if ((rc > 128)); then
        buf+="$part"
        # Progress bars rewrite one line with \r and never end it: hand them over without printing.
        if [[ "$buf" == *$'\r'* ]] && declare -F on_progress > /dev/null; then
          local bar="${buf%%+($'\r')}"
          on_progress "${bar##*$'\r'}"
          buf="${buf##*$'\r'}"
        fi
      else
        [[ -n "$buf$part" ]] && ui_line "$buf$part"
        break
      fi
    fi
    ui_draw
  done
  exec 3<&-
  wait "$UI_PID" 2> /dev/null
  rc=$?
  UI_PID=""
  trap - INT TERM
  printf '%s\e[?25h' "$ESC_CLEAR"
  return $rc
}

ui_finish() { # ui_finish <exit code> <what>
  printf '\n'
  if (($1 == 0)); then
    ui_ok "$2 beendet nach $(ui_time $SECONDS)."
  else
    ui_fail "$2 ist fehlgeschlagen (Code $1) nach $(ui_time $SECONDS)."
    if ((${#UI_FAILS[@]})); then
      printf '\n  %sLetzte Fehlerzeilen:%s\n' "$C_BOLD" "$C_OFF"
      local f start=$((${#UI_FAILS[@]} > 8 ? ${#UI_FAILS[@]} - 8 : 0))
      for f in "${UI_FAILS[@]:start}"; do printf '  %s%s%s\n' "$C_RED" "${f:0:200}" "$C_OFF"; done
    fi
  fi
  printf '  %sLog: %s%s\n\n' "$C_DIM" "$(ui_path "$UI_LOG")" "$C_OFF"
}
