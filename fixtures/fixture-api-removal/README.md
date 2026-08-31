# fixture-api-removal

The compile-failure fixture (PLAN §8.2): a code migration the agent must diagnose
and patch, not just a version bump.

## The mechanism

- Baseline (green): `EscapeSqlFormatter` calls
  `org.apache.commons.lang.StringEscapeUtils.escapeSql(input)` from
  `commons-lang:commons-lang:2.6`.
- Upgrade goal: `org.apache.commons:commons-lang3:3.14.0` — this is a **groupId +
  artifactId change** (`commons-lang:commons-lang` → `org.apache.commons:commons-lang3`),
  and the `escapeSql` method **does not exist anywhere in lang3** (SQL-escaping was
  removed from the commons-lang3 family; only careful parameterization is advised).
- After the coordinate swap the build fails with a **javac "cannot find symbol"
  error naming the removed type** — a precise failure signal. (DRIFT, absorbed per
  §13.3 in the phase-1 report: javac names `StringEscapeUtils`, the removed TYPE,
  not the method `escapeSql` — with lang3 the whole `org.apache.commons.lang`
  package is absent, so the import fails before the method is checked.)
  ```
  [ERROR] .../EscapeSqlFormatter.java:[3,31] package org.apache.commons.lang does not exist
  [ERROR] .../EscapeSqlFormatter.java:[17,16] cannot find symbol
  [ERROR]   symbol:   variable StringEscapeUtils
  [ERROR]   location: class com.example.removal.EscapeSqlFormatter
  ```
- Expected resolution (what the demo/eval asserts): one repair loop — diagnosis
  reads the compile error, a `CodePatch` replaces the call with an equivalent local
  implementation, the diff is validated (L1/L2) and the compile becomes green.
  Stages must include `Repairing`; `maxAttempts: 10`.

## How to reproduce the breakage (scripted, authoring-time)

```bash
cp -r fixtures/fixture-api-removal /tmp/swap-check
sed -i -e 's|commons-lang</groupId>|org.apache.commons</groupId>|' \
       -e 's|<artifactId>commons-lang</artifactId>|<artifactId>commons-lang3</artifactId>|' \
       -e 's|<version>2.6</version>|<version>3.14.0</version>|' \
       /tmp/swap-check/pom.xml
mvn -q -f /tmp/swap-check/pom.xml compile   # must FAIL naming escapeSql
```

This is the breakage the agent will face; FixtureSanityTest asserts it
deterministically.
