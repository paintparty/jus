#!/bin/sh

set -eu

clear_spinner() {
  clear_width=${1:-1}
  printf '\r%*s\r' "$clear_width" ''
}

run_spinner() {
  state_dir=$1
  label=$2
  target_pid=$3
  ready_file="$state_dir/ready"
  stopped_file="$state_dir/stopped"
  width_file="$state_dir/width"
  # The launcher selects a single-cell glyph so terminal clearing stays aligned.
  logo=${JUS_SPINNER_LOGO:?missing single-cell spinner logo}
  # Keep one frame per line so leading/trailing spaces are preserved.
  frames=$(printf '%s\n' "$logo " "$(printf '\033[2m%s\033[0m ' "$logo")" '  ' "$(printf '\033[2m%s\033[0m ' "$logo")")
  message="Starting $label..."
  frame_width=2
  line_width=$((${#message} + frame_width + 1))

  stop() {
    trap - EXIT HUP INT TERM
    clear_spinner "$line_width" || true
    : > "$stopped_file"
    exit 0
  }

  trap stop EXIT HUP INT TERM
  printf '%s\n' "$line_width" > "$width_file"
  : > "$ready_file"

  while :; do
    while IFS= read -r frame; do
      sleep 0.2 || true
      status=$(ps -o stat= -p "$target_pid" 2>/dev/null || true)
      if [ -z "$status" ] || [ "${status#Z}" != "$status" ]; then
        stop
      fi
      printf '\r%s Starting %s...' "$frame" "$label"
    done <<EOF
$frames
EOF
  done
}

stop_spinner() {
  state_dir=${JUS_SPINNER_STATE:?missing spinner state}
  spinner_pid=${JUS_SPINNER_PID:?missing spinner pid}
  stopped_file="$state_dir/stopped"
  width_file="$state_dir/width"

  kill -TERM "$spinner_pid" 2>/dev/null || true

  attempts=0
  while [ ! -f "$stopped_file" ] && [ "$attempts" -lt 200 ]; do
    sleep 0.01
    attempts=$((attempts + 1))
  done

  if [ ! -f "$stopped_file" ]; then
    if [ -f "$width_file" ]; then
      clear_width=$(sed -n '1p' "$width_file")
    else
      clear_width=1
    fi
    clear_spinner "$clear_width"
    printf '%s\n' 'Spinner did not acknowledge terminal handoff.' >&2
    return 1
  fi

  rm -f "$state_dir/ready" "$state_dir/stopped" "$width_file"
  rmdir "$state_dir" 2>/dev/null || true
}

case "${1:-}" in
  run)
    shift
    run_spinner "$@"
    ;;
  stop)
    stop_spinner
    ;;
  *)
    printf 'Usage: %s run <state-dir> <label> <target-pid>|stop\n' "$0" >&2
    exit 2
    ;;
esac
