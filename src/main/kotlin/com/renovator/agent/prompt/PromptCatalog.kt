package com.renovator.agent.prompt

import org.springframework.core.io.ClassPathResource

// LEARN[018] Placeholder-echo: LLMs imitate the exemplar's VALUES, not its intent
// Why this way: a prompt teaches by example. If the example carries placeholder
//   values ({"groupId": "...", "artifactId": "...", "fromVersion": "...", "toVersion": "..."}),
//   the model's job is to substitute the real values — but the model EMULATES the
//   exemplar instead, and it echoes the placeholder or, worse, invents coordinates
//   that "look like" the shape. That is exactly what the live eval caught
//   (phase-6 remediation): a schema with "..." produced plans like
//   "migrate target-artifact:2.0.0" / "...:..." / jackson-databind and commons-io
//   from a fixture that declared none of them — every plan was L3-rejected and the
//   run escalated. The fix is grounded exemplars: the prompt carries the REAL
//   coordinates (the current dependency from the repo model + the goal's toVersion)
//   and the example uses concrete, valid, real-looking values — there is nothing
//   for the intent to be mistaken about. Keep the JSON shape; the exemplar just
//   shows truth.
// Good sides: the model's plan is anchored to reality, not to a template; the
//   deterministic judge still rejects anything wrong (the validation boundary never
//   trusted the prompt in the first place — LEARN[006]); a grounded example helps
//   weak and strong models alike (the failure was model-INDEPENDENT, which is how we
//   knew the prompt, not the model, was the variable).
// Drawbacks: the prompt is longer (the repo model + goal are serialized in), and a
//   prompt edit is a gold change that can shift live behavior — it is a configured,
//   disclosed change (a LEARN + a §13.3 note), never a silent tweak.
// Concept: think of it as "show, don't distract." A schema with "..." is an
//   invitation to imitate; a schema with a real value is a constraint.
// See also: PLAN §8 (fixtures), LEARN[006] (boundary is code), LEARN[016], phase-6
//   remediation (baseline -> stronger model -> prompt fix is the recorded sequence)

/**
 * Prompts live ONLY here (PLAN §10.5: one versioned location under
 * src/main/resources/prompts; the protocol checker hard-fails prompt-shaped
 * literals elsewhere from Phase 3 on). Editing a prompt is a commit of its own.
 */
class PromptCatalog {
    private fun load(name: String): String = ClassPathResource("prompts/$name.st").inputStream.bufferedReader().readText()

    fun proposePlan(): String = load("propose_plan")

    fun diagnoseFailure(): String = load("diagnose_failure")

    fun proposePatch(): String = load("propose_patch")
}
