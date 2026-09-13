# Verification branch

This branch is byte-identical to `main` apart from this file. It exists only to
let the `Build` workflow run `:app:assembleRelease` over the current `main` tree
without being cancelled by the concurrent pushes to `main` (the workflow sets
`cancel-in-progress: true` per ref).

Safe to delete.
