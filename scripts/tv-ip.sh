# Sourced by the scripts that talk to the TV: sets TV from $1, else $TV_IP, else TV_IP= in the repo's .env.
# shellcheck shell=bash
tv_ip() {
  local repo; repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
  TV=${1:-${TV_IP:-$(sed -n 's/^TV_IP=//p' "$repo/.env" 2>/dev/null)}}
  [ -n "$TV" ] || { echo "No TV address: pass <tv-ip>, set TV_IP, or put TV_IP=<tv-ip> in $repo/.env" >&2; exit 1; }
}
