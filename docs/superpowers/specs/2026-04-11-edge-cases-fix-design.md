# Fix: Edge Cases in maven-central-publish Plugin

## Problem

Multiple edge cases cause artifact loss, resource leaks, and poor error handling:

1. `configureContent()` eagerly snapshots artifacts at configuration time — late plugins (KMP, AGP) miss the window
2. `afterEvaluate` ordering — KMP calls `publication.from(component)` at `AfterFinaliseCompilations` stage, which is AFTER `afterEvaluate`
3. No `dropDeployment` on failure/timeout — orphaned deployments linger in Portal
4. `checksumFiles` filters by `file().exists()` — masks dependency bugs
5. HttpClient resource leak on Java 17-20 — cosmetic, document in KDoc
6. HTTP 429 not retried — rate limiting causes immediate failure
7. `DuplicatesStrategy.EXCLUDE` silently drops duplicate artifacts
8. Multipart filename not escaped — special chars break upload
9. `InterruptedException` in RetryHandler — interruption status lost

## Decomposition

Three independent sub-projects, executed in order:

### Sub-project A: Lazy Evaluation Pipeline (issues 1, 2, 4, 7)

Core architectural fix — move artifact resolution from configuration time to execution time.

### Sub-project B: API Client Hardening (issues 5, 6, 8, 9)

Isolated client fixes — retry, escaping, thread safety.

### Sub-project C: Deployment Lifecycle (issue 3)

Error handling — drop deployments on failure/timeout.

---

## Sub-project A: Lazy Evaluation Pipeline

### Root Cause

`ZipDeploymentTask.configureContent()` calls `.finalizeValueOnRead().get()` during task configuration in `afterEvaluate`. This freezes the artifact list. KMP adds artifacts at `AfterFinaliseCompilations` (later than `afterEvaluate`), AGP adds components in its own `afterEvaluate` (order-dependent). Late artifacts are silently excluded from the ZIP.

### Design

#### A1: Lazy `configureContent()` — move to execution time

Replace eager `.get()` iteration with execution-time resolution by overriding `copy()`:

```kotlin
@TaskAction
override fun copy() {
    publications.get().forEach { info ->
        info.checksumTask?.let { checksumTask ->
            from(checksumTask.flatMap { it.checksumFiles }) {
                into(info.artifactPath)
            }
        }
        info.artifacts.get().forEach { artifactInfo ->
            from(artifactInfo.file()) {
                into(info.artifactPath)
                rename { artifactInfo.artifactName }
            }
        }
    }
    super.copy()
}
```

Remove the `configureContent()` method entirely. Remove all `configureContent()` calls from `MavenCentralUploaderPlugin.kt`.

Remove `finalizeValueOnRead()` calls — providers evaluate lazily at execution time.

#### A2: Remove `file().exists()` filter from `CreateChecksumTask`

Remove the filter from both `checksumFiles` provider (line 69) and `createChecksums()` action (line 100). If a file doesn't exist at execution time, it's a task dependency bug that should fail loudly.

#### A3: `DuplicatesStrategy.FAIL`

Replace `DuplicatesStrategy.EXCLUDE` with `DuplicatesStrategy.FAIL` in `ZipDeploymentTask.init`. Duplicate paths cause a build failure with clear error message from Gradle.

### Affected Files

- `ZipDeploymentTask.kt` — replace `configureContent()` with `copy()` override, change duplicates strategy
- `CreateChecksumTask.kt` — remove `file().exists()` filters
- `MavenCentralUploaderPlugin.kt` — remove `configureContent()` calls

---

## Sub-project B: API Client Hardening

#### B1: HttpClient lifecycle documentation

Add KDoc to `close()` explaining Java 17-20 behavior. No code change — `HttpClient` on pre-21 JVMs manages connections automatically.

#### B2: HTTP 429 retry

In `MavenCentralApiClientImpl.executeRequestWithRetry()`, add 429 to retriable status codes:

```kotlin
if (result is HttpResponseResult.Error && (response.statusCode() >= 500 || response.statusCode() == 429)) {
    throw RetriableHttpException(response.statusCode(), "Retriable HTTP error")
}
```

#### B3: Multipart filename escaping

In `MavenCentralApiClientImpl.filePart()`, escape special characters:

```kotlin
val sanitizedFilename = file.fileName.toString()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
```

#### B4: InterruptedException handling

In `RetryHandler.executeWithRetry()`, wrap `Thread.sleep` to restore interruption status:

```kotlin
try {
    Thread.sleep(delayMillis)
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw e
}
```

### Affected Files

- `MavenCentralApiClientImpl.kt` — B1, B2, B3
- `RetryHandler.kt` — B4

---

## Sub-project C: Deployment Lifecycle

#### C1: Drop deployment on failure/timeout

In `PublishBundleMavenCentralTask.publishBundle()`, wrap `waitForDeploymentCompletion` in try-catch:

```kotlin
try {
    waitForDeploymentCompletion(...)
} catch (e: Exception) {
    tryDropDeployment(apiClient, creds, deploymentId)
    throw e
}
```

`tryDropDeployment` logs warning on failure, never throws. Always attempts drop — Sonatype API will return an error if the deployment is in a non-droppable state (PUBLISHING/PUBLISHED), which is handled gracefully by the try-catch in `tryDropDeployment`.

### Affected Files

- `PublishBundleMavenCentralTask.kt` — add try-catch and `tryDropDeployment`

---

## Testing

### Integration Tests (TDD — write first)

| Test | Scenario | Validates |
|------|----------|-----------|
| `late afterEvaluate artifact addition` | Plugin adds `publication.from(component)` in late `afterEvaluate` | ZIP contains late artifacts (A1) |
| `KMP jvm + linuxX64 publish` | Real KMP plugin, full publish | All targets in bundle, lazy resolution (A1, existing) |
| `KMP publish includes all target artifacts` | Real KMP, verify ZIP content per target | Artifacts for each target + metadata (A1) |
| `checksums created for all artifacts` | Artifacts with task dependencies | No exists() filter, all checksums present (A2) |
| `duplicate artifact paths should fail` | Two artifacts with same path | Build fails with error (A3) |
| `retry on HTTP 429` | Mock returns 429 then 201 | Retry works, upload succeeds (B2) |
| `multipart filename escaping` | File with `"` in name | Correct Content-Disposition header (B3) |
| `InterruptedException preserves thread state` | Interrupt during retry sleep | Thread.interrupted() = true (B4) |
| `dropDeployment on FAILED status` | Mock API returns FAILED | drop called (C1) |
| `dropDeployment on timeout` | Mock API returns PENDING x maxChecks | drop called after timeout (C1) |
| `drop failure is non-fatal` | drop throws exception | Original error propagated, drop failure logged (C1) |
| `java-library publish` | Standard Java library | Regression (existing) |
| `java-platform BOM publish` | BOM | Regression (existing) |

### Late afterEvaluate simulation

For testing AGP-like behavior without Android SDK:

```kotlin
// In test build.gradle.kts — simulates AGP's afterEvaluate pattern
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("lateLib") {
                from(components["java"])
            }
        }
    }
}
```

This exactly reproduces the AGP pattern where components are attached in `afterEvaluate`.

## Edge Cases

1. **KMP + lazy pipeline**: Publications exist early but `from(component)` deferred — lazy resolution catches this
2. **AGP-like pattern**: Components created in `afterEvaluate` — covered by simulation test
3. **Drop during PUBLISHING state**: Sonatype only allows drop in FAILED/VALIDATED — don't attempt drop if last known state was PUBLISHING/PUBLISHED
4. **Concurrent retry + interrupt**: InterruptedException during backoff sleep — thread status preserved, exception propagated
5. **Empty publication after lazy eval**: If no artifacts resolve at execution time — ZIP is empty, upload validation catches this (existing behavior)

## Out of Scope

- Bearer vs Basic auth (issue 5a) — works as-is, not changing
- SNAPSHOT validation — Sonatype accepts snapshots
- 413 Payload Too Large — requires bundle splitting, separate project
- Configuration cache serialization of PublicationInfo — pre-existing, separate issue
