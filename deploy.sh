#!/usr/bin/env bash
# Builds Elf Desk and swaps it into the desk that house-elves installed, then restarts it.
#   ./deploy.sh            run the tests, build, install, restart
#   ./deploy.sh --no-tests skip the tests
set -euo pipefail
cd "$(dirname "$0")"

APP="${HOUSE_ELVES_DATA:-$HOME/.local/share/house-elves}/app/desk"
if [ ! -d "$APP" ]; then
  echo "No installed desk in $APP: run house-elves install first." >&2
  exit 1
fi

if [ "${1:-}" = "--no-tests" ]; then ./mvnw -q -B package -DskipTests; else ./mvnw -q -B package; fi

# Replace the app atomically: build next to it, then swap.
rm -rf "$APP.new" && cp -r target/quarkus-app "$APP.new"
rm -rf "$APP.old" && mv "$APP" "$APP.old" && mv "$APP.new" "$APP"

if [ "$(uname)" = Darwin ]; then
  launchctl kickstart -k "gui/$(id -u)/io.github.house-elves.desk"
else
  systemctl --user restart house-elves-desk.service
fi

for i in $(seq 1 60); do
  if curl -fs -o /dev/null "http://127.0.0.1:${HOUSE_ELVES_PORT:-7777}/q/health/ready"; then echo "Elf Desk is up: house-elves open"; exit 0; fi
  sleep 1
done
echo "Elf Desk didn't become ready; see: house-elves logs desk" >&2
exit 1
