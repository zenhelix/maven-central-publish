# Bundle Splitting for Large Maven Central Deployments

## Problem

When aggregating multiple modules into a single deployment bundle (`zipDeploymentAllModules`), the resulting ZIP can exceed Maven Central's upload size limit, causing a 413 Payload Too Large error. The plugin currently has no size awareness or splitting capability.

## Solution

Automatically split aggregated bundles into multiple smaller ZIPs grouped by module, each within a configurable size threshold. Upload as separate deployments with atomic rollback semantics.

## Configuration

New property in `mavenCentralPortal` extension:

```kotlin
mavenCentralPortal {
    maxBundleSize = 256.megabytes  // default: 256MB
}
```

- Stored internally as `Long` in bytes
- Convenience extensions: `Int.megabytes`, `Int.gigabytes`
- Validation: must be positive, fail at configuration time otherwise
- Applies only to aggregated tasks (`zipDeploymentAllModules`). Per-module tasks are unaffected.

## Bundle Splitting Algorithm

Splitting occurs at the `zipDeploymentAllModules` stage, after all subprojects have assembled their artifacts.

### Steps

1. Calculate the total artifact size of each module by summing the sizes of all uncompressed files (jar, pom, asc, checksums).
2. Validate that no single module exceeds `maxBundleSize`. If one does, fail with:
   `"Module ':module-name' artifacts size (XXX MB) exceeds maxBundleSize (256 MB). Reduce artifact size or increase maxBundleSize."`
3. Group modules into chunks using **first-fit decreasing**:
   - Sort modules by size descending.
   - Place each module into the first chunk where it fits.
   - If no existing chunk has room, create a new one.
4. If all modules fit in one chunk — produce a single ZIP (identical to current behavior).
5. If multiple chunks — produce separate ZIPs: `projectName-deployment-1-version.zip`, `projectName-deployment-2-version.zip`, etc.

### Why first-fit decreasing

Minimizes the number of deployments while keeping implementation simple and predictable. Well-understood bin-packing heuristic.

### Size calculation

Sizes are calculated on uncompressed files (before ZIP). This is conservative — the actual ZIP will be smaller — which provides a safety margin against API limits.

## Upload and Atomicity

### Upload flow

1. Upload chunks **sequentially** (not parallel) — simpler error control, avoids API rate issues.
2. Each chunk → `POST /api/v1/publisher/upload` → receives a deployment ID.
3. Collect all deployment IDs as uploads succeed.

### Automatic mode switching

When the bundle splits into multiple chunks and the user has configured `publishingType = AUTOMATIC`:

- Switch all chunks to `USER_MANAGED` mode for upload.
- Wait for all chunks to reach `VALIDATED` state.
- Publish all chunks sequentially.
- Log: `"Bundle was split into N chunks. Switching to USER_MANAGED mode for atomic deployment. All chunks will be published after successful validation."`

If only one chunk — `AUTOMATIC` remains as configured.

### Atomic rollback

If upload or validation of any chunk fails:

1. Stop uploading remaining chunks.
2. Drop all previously uploaded deployments via `DELETE /api/v1/publisher/deployment/{id}`.
3. Fail the build with: `"Deployment chunk N failed: <reason>. Rolled back N-1 previously uploaded deployments."`

## Polling and Status Monitoring

### Multi-deployment polling

After all chunks are uploaded:

1. Poll status of **every** deployment in each round.
2. Wait `statusCheckDelay` between rounds.
3. A round completes when all deployments reach a terminal state (`VALIDATED`, `PUBLISHED`, `FAILED`).
4. If any deployment reaches `FAILED` — do not wait for others, immediately drop all and fail.

### Logging

```
Bundle split into 3 chunks: [chunk-1: 230MB (modules: core, api, utils), chunk-2: 245MB (modules: web, security), chunk-3: 180MB (modules: data, cache)]
Uploading chunk 1/3... OK (deployment: abc-123)
Uploading chunk 2/3... OK (deployment: def-456)
Uploading chunk 3/3... OK (deployment: ghi-789)
Validating deployments... [1/3 VALIDATED, 2/3 VALIDATING, 3/3 PENDING]
Validating deployments... [3/3 VALIDATED]
Publishing all deployments...
Published successfully.
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Single module > `maxBundleSize` | Fail before upload: `"Module ':X' size (NNN MB) exceeds maxBundleSize"` |
| Upload error (network, 5xx) | Retry with existing logic (3 attempts, exponential backoff). If exhausted — drop uploaded, fail |
| 413 from API | Should not happen (size controlled), but if it does — drop uploaded, fail with recommendation to decrease `maxBundleSize` |
| Chunk validation `FAILED` | Drop all deployments, fail with validation error details from API |
| Drop fails during rollback | Log warning per failed drop, still fail the build. User cleans up manually via portal |
| Publish fails for one chunk | Drop remaining unpublished, fail with warning that some chunks may already be published (API limitation) |

The publish-phase partial failure is the only scenario where full atomicity is impossible. The plugin explicitly communicates this in the error message.

## Backward Compatibility

- Default `maxBundleSize = 256MB` — projects under threshold behave identically to today (single ZIP, single deployment).
- No new required configuration.
- Existing `mavenCentralPortal { }` DSL is extended, not broken.
- Per-module tasks (`publishBundleMavenCentral<Module>`) are not affected.

## Testing

- **Unit:** first-fit decreasing grouping algorithm — correct packing, edge cases (one module, module = exact threshold, empty list, all modules fit in one chunk).
- **Unit:** module size vs threshold validation — fail on exceed.
- **Unit:** `AUTOMATIC` → `USER_MANAGED` switch logic when split occurs.
- **Integration:** atomic rollback — mock API, upload 3 chunks, fail on 2nd, verify 1st is dropped.
- **Integration:** happy path — 3 chunks, all validated, all published.
