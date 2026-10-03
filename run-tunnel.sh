#!/usr/bin/env bash
# Expose the app's server with a Cloudflare quick tunnel (run in Termux on the same phone).
# Usage: ./run-tunnel.sh [port]      (default 8080)
PORT="${1:-8080}"

if ! curl -fs "http://127.0.0.1:${PORT}/status" >/dev/null; then
  echo "Server not answering on port ${PORT}. Open LiteWeb Extractor first (notification should say 'Server running')."
  exit 1
fi

# 127.0.0.1 instead of 'localhost' avoids cloudflared trying ::1 first.
exec cloudflared tunnel --url "http://127.0.0.1:${PORT}"
