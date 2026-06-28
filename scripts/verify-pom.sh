#!/bin/bash
# Assert the publishToMavenLocal POM for the SDK artifacts BEFORE the immutable
# one-shot remote publish to GitHub Packages.
#
# GitHub Packages 0.1.0 is immutable, so we prove the POM/coordinate locally
# first: run publishToMavenLocal for core + client, then grep each resulting
# ~/.m2 POM for the MIT license, the locked groupId, and the artifactId.
#
# Usage:
#   ./scripts/verify-pom.sh
#   JAVA_HOME=$JAVA21_HOME ./scripts/verify-pom.sh   # the Gradle gates need JDK 21
#
# Exits non-zero with a clear message if any assertion fails.

set -euo pipefail

GROUP_ID="com.jh.coroutine-visualizer"
VERSION="0.1.0"
GROUP_PATH="com/jh/coroutine-visualizer"
M2_REPO="${HOME}/.m2/repository"
ARTIFACTS=("coroutine-viz-core" "coroutine-viz-client")

# Resolve repo root so the script works from any CWD.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
BACKEND_DIR="${REPO_ROOT}/backend"

echo "==> Publishing core + client to mavenLocal (${M2_REPO})"
(
  cd "${BACKEND_DIR}"
  ./gradlew :coroutine-viz-core:publishToMavenLocal :coroutine-viz-client:publishToMavenLocal
)

FAILED=0

for artifact in "${ARTIFACTS[@]}"; do
  POM="${M2_REPO}/${GROUP_PATH}/${artifact}/${VERSION}/${artifact}-${VERSION}.pom"
  echo "==> Asserting POM: ${POM}"

  if [[ ! -f "${POM}" ]]; then
    echo "    FAIL: POM not found for ${artifact} (publishToMavenLocal did not produce it)"
    FAILED=1
    continue
  fi

  if ! grep -q "<groupId>${GROUP_ID}</groupId>" "${POM}"; then
    echo "    FAIL: groupId ${GROUP_ID} not found in ${artifact} POM"
    FAILED=1
  fi

  if ! grep -q "<artifactId>${artifact}</artifactId>" "${POM}"; then
    echo "    FAIL: artifactId ${artifact} not found in ${artifact} POM"
    FAILED=1
  fi

  if ! grep -q "<version>${VERSION}</version>" "${POM}"; then
    echo "    FAIL: version ${VERSION} not found in ${artifact} POM"
    FAILED=1
  fi

  if ! grep -q "MIT" "${POM}"; then
    echo "    FAIL: MIT license not found in ${artifact} POM"
    FAILED=1
  fi

  if [[ "${FAILED}" -eq 0 ]]; then
    echo "    OK: ${GROUP_ID}:${artifact}:${VERSION} (MIT) asserted"
  fi
done

if [[ "${FAILED}" -ne 0 ]]; then
  echo "==> verify-pom.sh: FAILED — fix the POM before the immutable remote publish."
  exit 1
fi

echo "==> verify-pom.sh: PASSED — core + client POMs carry the MIT license + locked coordinate."
