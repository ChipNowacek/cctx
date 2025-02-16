# Codebase Change Transaction (CCTX)

I cannot chase code around all the time. I need to deal with changes in ways that don't rob me of all my time and concerns. Creating changes as transactions are what I'm trying to structure. If the transaction doesn't do as we want, we roll it back, just like every other transaction.

Maybe a CCTX is to programming what a branch is to code.

## Environment
When transacting a code change, `$PROJECT_ROOT` needs to match the `projects.edn->:projects->:project->:project-root` for the project specified when the cctx was created.

Each CCTX includes a validation function that checks:
- `$PROJECT_ROOT` environment variable is set
- `$PROJECT_ROOT` matches the project root where the CCTX was created

If validation fails, the transaction will not proceed.