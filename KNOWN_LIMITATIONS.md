# KNOWN_LIMITATIONS.md

Deferred-work ledger (§10.2 of PLAN.md). Every `TODO(review) KL-NN` marker in code
maps to exactly one live row here; every non-pre-declared row maps to exactly one
marker. Struck-through rows are historical (closing commit in the rationale).

| KL-NN | Title | User-visible | Pre-declared | Rationale |
|---|---|---|---|---|
| KL-01 | single-process, single-run-at-a-time agent | user-visible: yes | pre-declared: yes | One agent process at a time; a second concurrent run is rejected by RunRegistry (409). Concurrent runs / distributed execution are out of scope (§12). |
| KL-02 | no authentication on the control API | user-visible: yes | pre-declared: yes | Sandbox containers are the security boundary; API is demo posture. Auth/multi-tenant are out of scope (§12). |
| KL-03 | Maven-only fixture scope (D4) | user-visible: yes | pre-declared: yes | Gradle / Kotlin-DSL fixture builds, non-Maven targets, and multi-module reactor targets are explicitly out of scope; fixtures are plain Maven projects (D4, §12). |
| KL-04 | LLM diagnoses are advisory | user-visible: yes | pre-declared: yes | A BuildDiagnosis is guidance for the planner; correctness is asserted only by deterministic build/test outcomes, never model confidence (§12). |
| KL-05 | eval N is small (4 fixtures) | user-visible: yes | pre-declared: yes | The fixture set is a smoke signal, not a benchmark; no statistical rigor is claimed (§12). |
| KL-06 | Ollama path may be slow on modest hardware | user-visible: yes | pre-declared: yes | Local-model latency influences timeouts; the live-eval threshold applies to whichever provider is configured (§12). |
| KL-12 | LLM client retries non-retryable quota errors | user-visible: no | pre-declared: no | Observed during the phase-0 live smoke: Embabel's LlmDataBindingProperties retried a 429 "no credits remaining" (quota-exhausted — non-retryable) ~10 times over ~24 s before failing, burning budget on an error that cannot succeed. Renovator's LLM-call wrapper (Phase 3) must map provider errors before retry: 429-quota → fail fast, 429-rate → retry with backoff. Reviewer carry-forward; numbered KL-12 because KL-07 is pre-declared by PLAN §10.2. |
