#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE="${GRADLE:-/tmp/opencode/gradle-8.12/bin/gradle}"
GH_REPO="rhoulou/TotalGirls"
SITE_URL="https://raw.githubusercontent.com/rhoulou/TotalGirls/main"
PROVIDERS=(BongaCamsProvider CamsodaProvider Cam4Provider ChaturbateProvider StripchatProvider)
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
with open("plugins.json") as f:
    data = json.load(f)
for p in data:
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

write_hashes() {
    python3 - <<'PY'
import hashlib, json
with open("plugins.json") as f:
    data = json.load(f)
for p in data:
    path = p["internalName"] + ".cs3"
    try:
        blob = open(path, "rb").read()
        p["fileSize"] = len(blob)
        p["fileHash"] = "sha256-" + hashlib.sha256(blob).hexdigest()
    except FileNotFoundError:
        pass
with open("plugins.json", "w") as f:
    json.dump(data, f, indent=4)
    f.write("\n")
PY
}

echo "== Recording file sizes + sha256 hashes =="
write_hashes

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
    for attempt in $(seq 1 5); do
        if git push origin main 2>/dev/null; then return 0; fi
        echo "  push rejected (attempt $attempt); syncing with origin..."
        git fetch origin main
        git pull --rebase origin main 2>&1 | tail -1 || true
        if [ -d .git/rebase-merge ] || [ -d .git/rebase-apply ]; then
            # Rebase in progress: the CI workflow only ever auto-commits *.cs3
            # artifacts, so regenerate them from our source build and recompute
            # plugins.json so the tree stays self-consistent.
            for f in "${PROVIDERS[@]}"; do
                cp "$f/build/$f.cs3" "$f.cs3"
            done
            write_hashes
            git add -A
            if ! GIT_EDITOR=true git rebase --continue 2>&1 | tail -1; then
                echo "ERROR: rebase conflicts not resolvable (source changed upstream?)" >&2
                git rebase --abort 2>/dev/null || true
                return 1
            fi
        fi
        git add -A
        if ! git diff --cached --quiet; then
            git commit --amend --no-edit 2>/dev/null || git commit -m "rebuild plugins"
        fi
    done
    echo "ERROR: push failed after 5 attempts" >&2
    return 1
}

commit() {
    git add -A
    if ! git diff --cached --quiet; then
        if [ -n "$NEW_VERSION" ]; then
            git commit -m "v$NEW_VERSION: per-provider settings (proxy/gender/rows)"
        else
            git commit -m "rebuild plugins"
        fi
    fi
}

echo "== Committing and pushing =="
commit
push

echo "== Waiting for raw.githubusercontent.com to serve version $EXPECTED =="
# raw.githubusercontent.com serves git main directly - the new version is
# visible as soon as the push propagates (seconds, no deploy pipeline).
for _ in $(seq 1 12); do
    sleep 10
    if curl -sk --max-time 20 "$SITE_URL/plugins.json" | \
       python3 -c "import json,sys
try:
    v=$EXPECTED
    d=json.load(sys.stdin)
    ok=[p for p in d if p['internalName'] in ('BongaCamsProvider','CamsodaProvider','Cam4Provider','ChaturbateProvider','StripchatProvider') and p['version']==v]
    sys.exit(0 if len(ok)==5 else 1)
except Exception:
    sys.exit(1)"; then
        echo "Live on raw: all providers at v$EXPECTED"
        exit 0
    fi
done

echo "ERROR: plugins.json not at v$EXPECTED on raw.githubusercontent.com" >&2
exit 1
