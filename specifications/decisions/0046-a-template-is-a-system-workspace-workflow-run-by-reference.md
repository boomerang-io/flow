# 0046 — A template is a system-workspace workflow that other workspaces run by reference

**Status:** accepted · **Date:** 2026-10-29

## Context

Workflow templates lived in their own collection, seeded and read-only, with no editor and a separate admin surface that duplicated the workflow logic. An
embedding product with a workspace per tenant needs one definition every tenant runs without copying; a starter a user copies stays a different thing.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the templates collection, add authoring routes | Templates are content, never run | A second editor and a second model for what is a workflow; nothing can run one without copying |
| B. A copy per workspace, kept in step by the caller | Few workspaces | N copies drift; the caller owns a reconcile loop |
| C. A `type` on the workflow, templates in the system workspace, a `templateRef` on a workflow that runs one | Shared definitions and tenant separation | Two typed fields, a migration, and a submit that resolves another workflow's revision |

## Decision

C. `WorkflowEntity.type` is `workflow`, `template` or `starter`, typed because create, submit and the editor decide from it; a `template` or `starter` body is
refused outside the system workspace (`workflow/WorkflowService.java:2645-2653`). A workflow with `templateRef` is a link: its own revision holds no tasks,
reads fill them from the template's pinned or latest revision (`:2682-2696,2709-2734`), and a submit runs that revision with the link as the run's workflow, so
the run, its parameter layers, quotas and audit are the link's workspace's (`:2299-2302`). The template cannot be deleted, or retyped, while a link names it (`:887-891,1045-1048`).
Starters stay a copy, as every compared product keeps the two apart, and `_0038__WorkflowTemplatesIntoSystem` moves the old collection into the system workspace
as starters. There are no template routes: a workspace's own workflow routes answer the system workspace's template when the workspace has no workflow of the
name (`:307-318,2470-2479`), and the first use, a submit or a saved setting, creates the link (`:672-680,740-745`). What decided it: a definition shared by
reference must create the run in the caller's unit from inputs alone (Argo's `workflowTemplateRef`, GitHub's `uses:`, Kestra's subflows).

## Consequences

- One editor and one model for workflows, templates and starters; admins version a template like any workflow and every link follows it at its next submit, or
  stays on its pinned version.
- A link is read-only in its tasks and params; its name, labels and triggers are the workspace's. A workflow of the workspace with a template's name hides the
  template there. A template's `runworkflow` task naming another template adopts the run's workspace's link the same way
  (`workflow/WorkflowService.java:2527-2543`).
- A workspace holds one link per template even when first uses are concurrent: each claims the pair with a `linksTemplate` edge from the workspace to the link,
  whose `_id` names the pair, so one wins and the rest wait for its link; deleting the link removes the claim (`workflow/WorkflowService.java:2545-2619`,
  `core/RelationshipService.java:354-408`).
- Not built, revisit when a workspace asks to publish its own templates: a link that re-points to another template, a template outside the system workspace, a
  starter that remembers its source.
