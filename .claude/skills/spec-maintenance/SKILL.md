---
name: spec-maintenance
description: Keep specifications/ true after a change. ALWAYS use after changing behaviour, endpoints, entities, indexes, the run lifecycle, the dispatcher contract, or security; and after choosing between designs. Reference docs are rewritten in place; decisions are appended as numbered records.
---

# Specification maintenance

`specifications/` holds exactly two kinds of document (see the table in CLAUDE.md):

| Kind | Where | You do |
| --- | --- | --- |
| Reference — how a subsystem works today | `specifications/<subsystem>.md` | Rewrite the sentences that are no longer true. Never append "as of <date>" notes or history; the doc describes the present. Keep it under 400 lines (aim 150–250). |
| Decision — why a choice was made | `specifications/decisions/NNNN-<title>.md` | Add a new record from `TEMPLATE.md`; add its row to `decisions/README.md`. Never edit an accepted record except to set `Status: superseded by NNNN`. |

## After a change, in this order

1. **Find the owning reference doc** — `architecture`, `execution-model`, `data-model`, `authorization`,
   `task-runtime`, `api-contract`, `performance`, `design-system`. If none fits, the change is probably not
   spec-worthy; do not create a new file without the maintainer's agreement.
2. **Rewrite, don't annotate.** Replace the now-false sentence with the true one and re-cite `path:line`.
   Delete anything the change made moot. Diff the doc: every remaining claim must still hold.
3. **Record the decision** only if you chose between real alternatives (a table with ≥ 2 options). Context
   in 2–3 sentences, options table, one-paragraph decision citing the implementing code, consequences with
   the trigger for revisiting. Under 40 lines.
4. **Bugs and plans are not specs.** A defect you found → a GitHub issue (type Bug, label `frontend`/`backend`).
   Work you did not do → an issue (type Task) or a sub-issue. Audits and findings logs are not kept.
5. **CLAUDE.md** changes only when a fact an agent needs every session changed (a module, a command, an
   invariant). Keep it under 200 lines.

## Language rules (from CLAUDE.md, restated because they are the ones people break)

- Name things; never use a project code (no epic, track, decision, question or gate numbers).
- No "verdict", "ruled", "as of track X". Say what is, cite the code.
- At most two abbreviations per document, each spelled out on first use.
- First sentence of a section is the point. Tables for anything with more than two dimensions.

## Done means

- [ ] The owning reference doc reads true against the code after your change, with citations re-checked.
- [ ] A decision record exists for any choice between alternatives, and the index row is added.
- [ ] Defects and deferred work are issues, not paragraphs.
- [ ] `grep -nE 'E[0-9]{1,2}\b|DD-[0-9]{2}|Q-[0-9]{3}|AM-[0-9]+' specifications/ CLAUDE.md` returns nothing.
