#!/system/bin/sh
# xdg-open shim — writes URL to trigger file, app's FileObserver opens it
URL_FILE="${PREFIX:-/data/data/kle.ljubitje.apai/files/usr}/tmp/.open-url"
echo "$1" > "$URL_FILE"
