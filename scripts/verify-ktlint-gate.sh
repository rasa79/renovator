#!/usr/bin/env bash
# Task 0.2 gate script: proves the ktlint wiring in BOTH directions —
#   (1) misformatted Kotlin must FAIL ./mvnw ktlint:check (negative)
#   (2) a clean tree must PASS (positive)
# The negative case is exercised on a throwaway scratch module under target/ so the
# repo itself is never touched; target/ is gitignored.
#
# Expected output:
#   FAIL confirmed (expected)
#   PASS confirmed
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SCRATCH="$ROOT/target/ktlint-gate"
rm -rf "$SCRATCH"
mkdir -p "$SCRATCH/scratch/src/bad"

cat > "$SCRATCH/scratch/pom.xml" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>scratch</groupId>
    <artifactId>ktlint-scratch</artifactId>
    <version>1.0</version>
    <build>
        <sourceDirectory>src</sourceDirectory>
        <plugins>
            <plugin>
                <groupId>com.github.gantsign.maven</groupId>
                <artifactId>ktlint-maven-plugin</artifactId>
                <version>3.7.1</version>
                <executions>
                    <execution>
                        <id>check</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>check</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
POM

# Two canonical ktlint violations: wildcard import (no-wildcard-imports) and
# trailing whitespace (no-trailing-spaces).
cat > "$SCRATCH/scratch/src/bad/Bad.kt" <<'KT'
package bad

import java.util.*

fun bad() {
    println("x")   
}
KT

if ./mvnw -q -f "$SCRATCH/scratch/pom.xml" \
    com.github.gantsign.maven:ktlint-maven-plugin:3.7.1:check > "$SCRATCH/negative.log" 2>&1; then
    echo "FAIL: ktlint accepted misformatted code — negative gate broken"
    exit 1
fi
if ! grep -q "Bad.kt" "$SCRATCH/negative.log"; then
    echo "FAIL: ktlint failure did not name the offending file"
    exit 1
fi
echo "FAIL confirmed (expected)"

# Positive: the real tree passes the same check goal that verify binds to.
./mvnw -q ktlint:check
echo "PASS confirmed"
