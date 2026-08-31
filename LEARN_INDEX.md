# LEARN_INDEX.md

Protocol index (§10.1 of PLAN.md): every `LEARN[NNN]` comment, its location
(computed by scripts/check_protocols.py), and a concept tag. Struck-through rows
are deleted comments kept for numbering history; numbers are never reused.

| # | Title | Location | Concept |
|---|---|---|---|
| 001 | One client abstraction, two providers: the OpenAI-compatible trick | src/main/kotlin/com/renovator/config/LlmProviderConfig.kt:9 | dual-provider env-only switch |
| 002 | The protocol lint is mechanical and load-bearing | scripts/check_protocols.py:24 | enforced-by-hook protocols |
| 003 | Judge before judged: why the fixtures land before the agent code | fixtures/README.md:10 | deterministic judge first |
| 004 | Reversibility: throwaway container + pristine copy; Docker CLI over Testcontainers | src/main/kotlin/com/renovator/execution/DockerSandboxRunner.kt:9 | sandbox reversibility + Excerpt budget |
