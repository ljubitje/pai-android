#!/data/data/kle.ljubitje.apai/files/usr/bin/sh
# Bun shim for Android — native bun is glibc-only, so delegate to node/tsx.
# Supports: bun run, bun install, bun --version, bunx

BUN_VERSION="1.2.0-android-shim"

case "$1" in
  --version|-v)
    echo "$BUN_VERSION"
    ;;
  --help|-h)
    echo "bun $BUN_VERSION (Android shim — delegates to node/tsx)"
    echo "Usage: bun run <script>  |  bun install  |  bun --version"
    ;;
  run)
    shift
    # Find the script argument (skip flags like --silent)
    SCRIPT=""
    ARGS=""
    for arg in "$@"; do
      case "$arg" in
        -*) ARGS="$ARGS $arg" ;;
        *)
          if [ -z "$SCRIPT" ]; then
            SCRIPT="$arg"
          else
            ARGS="$ARGS $arg"
          fi
          ;;
      esac
    done
    if [ -z "$SCRIPT" ]; then
      echo "bun-shim: missing script argument" >&2
      exit 1
    fi
    case "$SCRIPT" in
      *.ts|*.tsx)
        exec tsx "$SCRIPT" $ARGS
        ;;
      *.js|*.mjs)
        exec node "$SCRIPT" $ARGS
        ;;
      *)
        # Could be a package.json script — try tsx first
        exec tsx "$SCRIPT" $ARGS
        ;;
    esac
    ;;
  install|add|remove|update|pm)
    # Delegate package management to npm
    exec npm "$@"
    ;;
  x)
    # bunx — run a package binary
    shift
    exec npx "$@"
    ;;
  create)
    shift
    exec npm create "$@"
    ;;
  *)
    # Default: try to run as a script via tsx
    if [ -f "$1" ]; then
      exec tsx "$@"
    else
      echo "bun-shim: unknown command '$1'" >&2
      echo "Run 'bun --help' for usage." >&2
      exit 1
    fi
    ;;
esac
