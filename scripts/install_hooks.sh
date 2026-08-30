#!/usr/bin/env bash
# Installs the Renovator protocol pre-commit hook (PLAN §10.4 / GW-4).
# The hook runs the mechanical protocol checker on every commit; --no-verify is
# forbidden by the plan and never used by this repository.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOK="$ROOT/.git/hooks/pre-commit"

cat > "$HOOK" <<'HOOK_FILE'
#!/usr/bin/env bash
# Renovator protocol pre-commit hook (installed by scripts/install_hooks.sh).
# The checker enforces LEARN format/index, TODO(review) <-> KNOWN_LIMITATIONS 1:1,
# and prompt-location rules. Note: no --phase-boundary here — at pre-commit time the
# index is necessarily dirty, so git-cleanliness is enforced at phase gates instead.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
python3 "$ROOT/scripts/check_protocols.py"
HOOK_FILE

chmod +x "$HOOK"
echo "pre-commit hook installed at $HOOK"
