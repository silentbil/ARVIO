#!/usr/bin/env bash
# ARVIO - Enable "Open in VLC" protocol handler for Linux
set -e

echo "================================================"
echo " ARVIO - Enable \"Open in VLC\" for Linux"
echo "================================================"
echo ""

VLC_BIN=$(command -v vlc 2>/dev/null || true)
if [ -z "$VLC_BIN" ]; then
  echo "[!] Could not find VLC on this system."
  echo "    Please install VLC (e.g., sudo apt install vlc / sudo dnf install vlc) and run this again."
  exit 1
fi

echo "[+] Found VLC at: $VLC_BIN"

BIN_DIR="$HOME/.local/bin"
APP_DIR="$HOME/.local/share/applications"
mkdir -p "$BIN_DIR" "$APP_DIR"

SCRIPT_PATH="$BIN_DIR/arvio-vlc"

cat << 'EOF' > "$SCRIPT_PATH"
#!/usr/bin/env bash
URL="$1"
# Strip leading vlc: and any slashes following it
URL="${URL#vlc:}"
while [[ "$URL" == /* ]]; do
  URL="${URL#/}"
done
# Repair mangled scheme separator (e.g. Chrome stripping colon https// -> https://, or collapsing slashes https:/ -> https://)
if [[ "$URL" =~ ^(https?)[:/]+(.*) ]]; then
  URL="${BASH_REMATCH[1]}://${BASH_REMATCH[2]}"
fi
if [ -n "$URL" ]; then
  exec vlc "$URL"
fi
EOF

chmod +x "$SCRIPT_PATH"
echo "[+] Installed handler script to: $SCRIPT_PATH"

DESKTOP_PATH="$APP_DIR/vlc-handler.desktop"
cat << EOF > "$DESKTOP_PATH"
[Desktop Entry]
Name=VLC ARVIO Link Handler
Comment=Open vlc:// links directly in VLC
Exec=$SCRIPT_PATH %u
Type=Application
Terminal=false
MimeType=x-scheme-handler/vlc;
NoDisplay=true
EOF

echo "[+] Created desktop entry at: $DESKTOP_PATH"

if command -v xdg-mime >/dev/null 2>&1; then
  xdg-mime default vlc-handler.desktop x-scheme-handler/vlc
  echo "[+] Registered vlc:// scheme with xdg-mime."
fi

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APP_DIR" 2>/dev/null || true
fi

echo ""
echo "================================================"
echo " Done! \"Open in VLC\" now works on web.arvio.tv"
echo "================================================"
echo "Go back to ARVIO and click \"Open in VLC\" on any source."
