#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE="${GRADLE:-/tmp/opencode/gradle-8.12/bin/gradle}"
GH_REPO="rhoulou/TotalGirls"
SITE_URL="https://rhoulou.github.io/TotalGirls"
PROVIDERS=(BongaCamsProvider CamsodaProvider)
NEW_VERSION="${1:-}"

tasks=()
for p in "${PROVIDERS[@]}"; do
    tasks+=(":$p:make")
done

if [ -n "$NEW_VERSION" ]; then
    echo "== Bumping version to $NEW_VERSION =="
    for p in "${PROVIDERS[@]}"; do
        sed -i -E "s/^version = [0-9]+/version = $NEW_VERSION/" "$p/build.gradle.kts"
    done
    python3 - "$NEW_VERSION" <<'PY'
import json, sys
v = int(sys.argv[1])
keep = {"BongaCamsProvider", "CamsodaProvider"}
with open("plugins.json") as f:
    data = json.load(f)
for p in data:
    if p["internalName"] in keep:
        p["version"] = v
with open("plugins.json", "w") as f:
    json.dump(data, f, indent=4)
    f.write("\n")
PY
fi

EXPECTED="$NEW_VERSION"
if [ -z "$EXPECTED" ]; then
    EXPECTED=$(python3 -c "import json; print([p['version'] for p in json.load(open('plugins.json')) if p['internalName']=='BongaCamsProvider'][0])")
fi

echo "== Building ${PROVIDERS[*]} =="
export ANDROID_HOME
"$GRADLE" --no-daemon "${tasks[@]}"

for p in "${PROVIDERS[@]}"; do
    cp "$p/build/$p.cs3" "$p.cs3"
done

echo "== Verifying manifests =="
for p in "${PROVIDERS[@]}"; do
    got=$(unzip -p "$p.cs3" manifest.json | python3 -c "import json,sys; print(json.load(sys.stdin)['version'])")
    echo "$p manifest version: $got"
    if [ "$got" != "$EXPECTED" ]; then
        echo "ERROR: $p manifest version $got != expected $EXPECTED" >&2
        exit 1
    fi
done

push() {
    git push origin main 2>/dev/null && return 0
    git pull --rebase origin main 2>&1 | tail -2 || true
    for f in "${PROVIDERS[@]}"; do
        git checkout --ours "$f.cs3" 2>/dev/null || true
    done
    git add -A
    GIT_EDITOR=true git rebase --continue 2>/dev/null || true
    git push origin main
}

commit() {
    git add -A
    if ! git diff --cached --quiet; then
        if [ -n "$NEW_VERSION" ]; then
            git commit -m "v$NEW_VERSION: official APIs via personal proxy (rebuild)"
        else
            git commit -m "rebuild plugins"
        fi
    fi
}

echo "== Committing and pushing =="
commit
push

echo "== Waiting for GitHub Pages deploy (version $EXPECTED) =="
for attempt in 1 2 3 4 5; do
    for _ in $(seq 1 12); do
        sleep 15
        if curl -sk --max-time 20 "$SITE_URL/plugins.json" | \
           python3 -c "import json,sys
try:
    v=$EXPECTED
    d=json.load(sys.stdin)
    ok=[p for p in d if p['internalName'] in ('BongaCamsProvider','CamsodaProvider') and p['version']==v]
    sys.exit(0 if len(ok)==2 else 1)
except Exception:
    sys.exit(1)"; then
            echo "Deploy live: both providers at v$EXPECTED"
            exit 0
        fi
    done
    echo "Deploy not live yet (attempt $attempt) - re-triggering"
    git commit --allow-empty -m "chore: re-trigger Pages deploy (v$EXPECTED)" 2>/dev/null || true
    push
done

echo "ERROR: Pages deploy still not live after retries" >&2
exit 1
