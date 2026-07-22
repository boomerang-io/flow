---
name: grill-me
description: A relentless one-question-at-a-time interview to stress-test a plan, design, or decision before acting on it. Use when the user says "grill me", wants their thinking stress-tested, or before committing to a significant v5 design direction.
disable-model-invocation: true
argument-hint: [the plan/decision/idea to grill]
---

# Grill Me

Adapted from Matt Pocock's `grilling` skill (github.com/mattpocock/skills).

Interview the user relentlessly about every aspect of the plan/decision until you reach a
shared understanding. Walk down each branch of the decision tree, resolving dependencies
between decisions one by one.

Rules:

1. **One question at a time.** Wait for the answer before the next question. Multiple
   questions at once are bewildering. (AskUserQuestion with a single question per call is
   fine; batching several is not — this differs from the Q-register walkthrough style.)
2. **Recommend an answer with every question.** Never ask open-ended without stating your
   pick and why.
3. **Look up facts; ask decisions.** If a fact is discoverable from the codebase, the
   specs (`specifications/`), or the Q-register, go read it — never ask the user for it.
   Only *decisions* go to the user.
4. **Do not act until the user confirms shared understanding.** The output of a grilling
   is an agreed position — record it (spec §10 Decisions / register entry per the v5
   process) before implementation starts.
5. Check what's already ruled first: `specifications/v5-enhancemnet.md` §10 (DD-01…),
   `consolidation-proposal.md` §10, `phase2b-decisions.md` — do not re-grill settled
   decisions unless the user brings new evidence.
