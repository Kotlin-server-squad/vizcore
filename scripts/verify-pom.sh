#!/bin/bash
# Assert the publishToMavenLocal POM for the SDK artifacts BEFORE the immutable
# one-shot remote publish to GitHub Packages.
#
# GitHub Packages 0.3.1 is immutable, so we prove the POM/coordinate locally
# first: run publishToMavenLocal for core + client, then grep each resulting
# ~/.m2 POM for the Apache-2.0 license (backend/LICENSE), the locked groupId, and the artifactId.
#
# Usage:
#   ./scripts/verify-pom.sh
#   JAVA_HOME=$JAVA21_HOME ./scripts/verify-pom.sh   # the Gradle gates need JDK 21
#
# Exits non-zero with a clear message if any assertion fails.

set -euo pipefail

GROUP_ID="com.jh.coroutine-visualizer"
VERSION="0.3.1"
# What consumers get on their classpath: apps on Kotlin 2.2 and Spring Boot's pinned
# kotlinx-coroutines must be able to use the SDK (see RuntimeRequirements.kt).
STDLIB_VERSION="2.2.21"
COROUTINES_VERSION="1.10.2"
SERIALIZATION_VERSION="1.9.0"
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

  # Per-artifact flag so an earlier artifact's failure cannot mislabel this one's
  # OK/FAIL line. FAILED stays a separate global accumulator for the final exit.
  artifact_failed=0

  if [[ ! -f "${POM}" ]]; then
    echo "    FAIL: POM not found for ${artifact} (publishToMavenLocal did not produce it)"
    FAILED=1
    continue
  fi

  if ! grep -q "<groupId>${GROUP_ID}</groupId>" "${POM}"; then
    echo "    FAIL: groupId ${GROUP_ID} not found in ${artifact} POM"
    artifact_failed=1
  fi

  if ! grep -q "<artifactId>${artifact}</artifactId>" "${POM}"; then
    echo "    FAIL: artifactId ${artifact} not found in ${artifact} POM"
    artifact_failed=1
  fi

  if ! grep -q "<version>${VERSION}</version>" "${POM}"; then
    echo "    FAIL: version ${VERSION} not found in ${artifact} POM"
    artifact_failed=1
  fi

  # Anchor to the license name element — a bare "Apache" substring would pass on a
  # comment, a URL, or a transitive dependency artifactId.
  if ! grep -q "<name>Apache License, Version 2.0</name>" "${POM}"; then
    echo "    FAIL: Apache-2.0 license element not found in ${artifact} POM"
    artifact_failed=1
  fi

  # Each dependency's <version> is on the line after its <artifactId>.
  if ! grep -A1 "<artifactId>kotlin-stdlib</artifactId>" "${POM}" | grep -q "<version>${STDLIB_VERSION}</version>"; then
    echo "    FAIL: kotlin-stdlib ${STDLIB_VERSION} not declared in ${artifact} POM"
    artifact_failed=1
  fi

  if ! grep -A1 "<artifactId>kotlinx-coroutines-core-jvm</artifactId>" "${POM}" | grep -q "<version>${COROUTINES_VERSION}</version>"; then
    echo "    FAIL: kotlinx-coroutines-core-jvm ${COROUTINES_VERSION} not declared in ${artifact} POM"
    artifact_failed=1
  fi

  if ! grep -A1 "<artifactId>kotlinx-serialization-json-jvm</artifactId>" "${POM}" | grep -q "<version>${SERIALIZATION_VERSION}</version>"; then
    echo "    FAIL: kotlinx-serialization-json-jvm ${SERIALIZATION_VERSION} not declared in ${artifact} POM"
    artifact_failed=1
  fi

  if [[ "${artifact_failed}" -eq 0 ]]; then
    echo "    OK: ${GROUP_ID}:${artifact}:${VERSION} (Apache-2.0, stdlib ${STDLIB_VERSION}, coroutines ${COROUTINES_VERSION}, serialization ${SERIALIZATION_VERSION}) asserted"
  else
    FAILED=1
  fi
done

if [[ "${FAILED}" -ne 0 ]]; then
  echo "==> verify-pom.sh: FAILED — fix the POM before the immutable remote publish."
  exit 1
fi

echo "==> verify-pom.sh: PASSED — core + client POMs carry the Apache-2.0 license + locked coordinate."
