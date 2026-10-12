#!/usr/bin/env bash
# Builds Elf Desk and deploys it to the local elf-desk.service. This is the only way changes reach the running desk.
#   ./deploy.sh            run the tests, build, install, restart
#   ./deploy.sh --no-tests skip the tests
set -euo pipefail
cd "$(dirname "$0")"

APP="$HOME/.local/share/elf-desk/app"
UNIT="$HOME/.config/systemd/user/elf-desk.service"
ENV_FILE="$HOME/.config/elf-desk/env"

if [ "${1:-}" = "--no-tests" ]; then ./mvnw -q -B package -DskipTests; else ./mvnw -q -B package; fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE (ELFDESK_GITHUB_CLIENT_ID, ELFDESK_GITHUB_CLIENT_SECRET, ELFDESK_RUNNER_TOKEN, ELFDESK_DB_PASSWORD)" >&2
  exit 1
fi

mkdir -p "$APP" "$(dirname "$UNIT")"
# Replace the app atomically: build next to it, then swap.
rm -rf "$APP.new" && cp -r target/quarkus-app "$APP.new"
rm -rf "$APP.old" && { [ -d "$APP" ] && mv "$APP" "$APP.old" || true; } && mv "$APP.new" "$APP"

if ! cmp -s deploy/elf-desk.service "$UNIT"; then
  cp deploy/elf-desk.service "$UNIT"
  systemctl --user daemon-reload
fi
systemctl --user enable elf-desk.service >/dev/null 2>&1 || true
systemctl --user restart elf-desk.service

for i in $(seq 1 60); do
  if curl -fs -o /dev/null http://localhost:7480/q/health/ready; then echo "Elf Desk is up: http://$(hostname -I | awk '{print $1}'):7480"; exit 0; fi
  sleep 1
done
echo "Elf Desk didn't become ready; see: journalctl --user -u elf-desk -n 50" >&2
exit 1
