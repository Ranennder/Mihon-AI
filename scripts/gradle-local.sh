#!/usr/bin/env bash
set -euo pipefail

task_repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$task_repo_root"

task_validation_dir="${XDG_CACHE_HOME:-$HOME/.cache}/mihon-ai-validation"
mkdir -p -- "$task_validation_dir"

exec flock --nonblock --conflict-exit-code 75 "$task_validation_dir/gradle.lock" \
    systemd-run --user --scope --unit="mihon-build-$$" \
    -p MemoryHigh=3G \
    -p MemoryMax=4G \
    -p MemorySwapMax=512M \
    -p CPUQuota=150% \
    nice -n 10 ./gradlew "$@" \
    --no-daemon --no-parallel --max-workers=2 \
    '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' \
    -Pkotlin.compiler.execution.strategy=in-process
