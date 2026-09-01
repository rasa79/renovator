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
| 005 | Kotlin for a Java engineer: the proposal types are the contract | src/main/kotlin/com/renovator/domain/Proposals.kt:8 | kotlin data-class/sealed contract |
| 006 | The enforcement boundary: validation is code, not prompts | src/main/kotlin/com/renovator/execution/UpgradeExecutor.kt:16 | enforcement-boundary principle |
| 007 | Normalize-then-match: why matching before normalizing is the classic whitelist bypass | src/main/kotlin/com/renovator/validation/PathWhitelistValidator.kt:8 | normalize-then-match whitelist |
| 011 | The typed blackboard is workflow-engine process variables | src/main/kotlin/com/renovator/agent/RenovatorAgent.kt:145 | typed blackboard = BPMN process variables |
| 017 | LLM retry taxonomy: which HTTP errors are retryable and why | src/main/kotlin/com/renovator/agent/llm/LlmCall.kt:3 | retry taxonomy for LLM APIs |
| 010 | Action-cost asymmetry: the planner prefers plans that fail cheap | src/main/kotlin/com/renovator/agent/RenovatorAgent.kt:80 | cost asymmetry, fail cheap |
| 009 | GOAP/dynamic planning vs static graph wiring — the canonical essay | src/main/kotlin/com/renovator/agent/RenovatorAgent.kt:39 | GOAP vs static graph |
| 008 | A real diff library, never regex: what unified-diff context lines are FOR | src/main/kotlin/com/renovator/validation/DiffApplyValidator.kt:7 | real diff library, no regex |
