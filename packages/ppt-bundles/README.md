# DSH PPT distributions

The two local dependencies are `dsh-ppt` and `dsh-ppt-composer`. Source, template projects, notices and the exact artifact hashes are maintained in [`../ppt-runtime/`](../ppt-runtime/README.md) and [`artifacts.json`](../ppt-runtime/artifacts.json).

The catalog contains 16 templates / 134 layouts, English previews and Chinese examples. Rebuild with `npm run ppt:build`, refresh lockfile integrity, then install and run the PPT tests. Do not restore retired archives or apply the former temporary runtime patches.

## CI mirror note (IMPORTANT)

`dsh-ppt-*.tgz` is ~10.5MB and exceeds the GitHub Git Data API 10MB per-blob limit, so it cannot be carried by the Git Data API mirror that syncs this repo to the `aim` build branch on `samcaicn/gloai`. It is git-ignored on purpose. Instead it is published as a release asset on the persistent `ppt-deps` pre-release of `samcaicn/gloai`, and `.github/workflows/build-exe-dmg.yml` downloads it into `packages/ppt-bundles/` (via `gh release download ppt-deps`) immediately before `npm install` on both the Windows and macOS build jobs. **Do not delete the `ppt-deps` release**, and keep the download step in the workflow, or `npm install` will fail with ENOENT in CI.
