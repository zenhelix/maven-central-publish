# DX Improvements Design Spec

## Overview

Comprehensive improvements to the maven-central-publish Gradle plugin focused on developer experience, API quality, configurability, and test coverage. The plugin is a public open-source product targeting the broader Gradle community.

## Approach: DX First

Prioritize developer experience and adoption. Make the plugin maximally convenient for new users first, then expand functionality.

---

## Phase 1: DX and Documentation

### 1.1 KDoc on Public API

Add KDoc to all public classes, interfaces, enums, and their public properties/methods that currently lack documentation.

**Classes requiring KDoc:**
- `MavenCentralUploaderExtension` — main DSL entry point, all public properties
- `MavenCentralUploaderCredentialExtension` — credential configuration, `isBearerConfigured`/`isUsernamePasswordConfigured`
- `BearerCredentialExtension` — bearer token property
- `UsernamePasswordCredentialExtension` — username/password properties
- `UploaderSettingsExtension` — all settings with default values and usage examples
- `PublishingMode` — enum values `AUTOMATIC` and `USER_MANAGED` with behavioral descriptions
- `MavenCentralApiClient` — interface and all 4 methods (`uploadDeploymentBundle`, `deploymentStatus`, `publishDeployment`, `dropDeployment`)
- `PublishBundleMavenCentralTask` — task purpose, inputs, behavior
- `PublishSplitBundleMavenCentralTask` — task purpose, when it's used vs regular publish

**Out of scope:** KDoc on internal/private classes, utility functions.

### 1.2 Visibility Narrowing

Change 7 public entities to `internal`:

| Class | Reason |
|-------|--------|
| `DefaultMavenCentralApiClient` | Only instantiated via `createApiClient()` factory, consumers use `MavenCentralApiClient` interface |
| `BundleChunker` | Only used internally by `SplitZipDeploymentTask` |
| `ModuleSize` | Helper data class for `BundleChunker` |
| `Chunk` | Helper data class for `BundleChunker` |
| `ChunkError` | Only used within chunking logic |
| `MavenCentralChunkException` | Only thrown from internal tasks |
| `RetryHandler` | Implementation detail of `DefaultMavenCentralApiClient`, not part of plugin contract |

### 1.3 Gradle Version Catalog

Create `gradle/libs.versions.toml` to replace all hardcoded dependency versions in `build.gradle.kts`.

**Version groups:**
- `kotlin` — Kotlin Gradle Plugin
- `jackson` — Jackson Module Kotlin
- `coroutines` — Kotlinx Coroutines Core, Kotlinx Coroutines Test
- `testing` — JUnit Jupiter, MockK, AssertJ, BouncyCastle

Refactor `build.gradle.kts` to use catalog references.

---

## Phase 2: Configurability

### 2.1 Configurable Retry/Backoff

Add new properties to `UploaderSettingsExtension`:

```kotlin
// New properties with defaults matching current hardcoded values
requestTimeout: Property<Duration>    // default: 5 minutes
connectTimeout: Property<Duration>    // default: 30 seconds
maxRetries: Property<Int>             // default: 3
retryBaseDelay: Property<Duration>    // default: 2 seconds
```

**Data flow:** Extension properties -> task `@Input` properties -> `DefaultMavenCentralApiClient` constructor parameters.

**DSL example:**
```kotlin
mavenCentralPortal {
    uploader {
        requestTimeout = Duration.ofMinutes(10)
        connectTimeout = Duration.ofSeconds(60)
        maxRetries = 5
        retryBaseDelay = Duration.ofSeconds(5)
    }
}
```

`MAX_BACKOFF_DELAY_MILLIS` (5 min cap) in `RetryHandler` remains a safety constant, not user-configurable.

### 2.2 POM Defaults/Helpers

Add optional `pom { }` block to `MavenCentralUploaderExtension`:

```kotlin
mavenCentralPortal {
    pom {
        name = "My Library"
        description = "A useful library"
        url = "https://github.com/org/repo"

        license { apache2() }

        developer {
            id = "user"
            name = "User Name"
            email = "user@example.com"
        }

        scm {
            fromGithub("org", "repo")
        }
    }
}
```

**Design decisions:**

1. **Defaults, not overrides.** Our `pom {}` block applies first. Standard Gradle `publishing { publications { mavenJava { pom { } } } }` applies after and can override any field. User values always win.

2. **License presets:** `apache2()`, `mit()`, `bsd2()`, `bsd3()` — cover 90% of open-source projects. Each sets `name`, `url`, `distribution = "repo"`.

3. **SCM shortcut:** `scm { fromGithub("org", "repo") }` generates:
   - `connection = "scm:git:git://github.com/org/repo.git"`
   - `developerConnection = "scm:git:ssh://github.com/org/repo.git"`
   - `url = "https://github.com/org/repo"`

4. **Multiple developers/licenses:** Both `developer {}` and `license {}` blocks are repeatable.

5. **Validation on publish:** In `PublishBundleMavenCentralTask` and `PublishSplitBundleMavenCentralTask`, before calling the API, validate that required POM fields are present (`name`, `description`, `url`, `licenses`, `developers`, `scm`) on all publications in the bundle. Fail with a clear error message listing missing fields per publication — before the upload attempt, not after a cryptic rejection from Sonatype.

**New extension classes:**
- `PomExtension` — top-level POM configuration
- `PomLicenseExtension` — license configuration with presets
- `PomDeveloperExtension` — developer info
- `PomScmExtension` — SCM configuration with shortcuts

---

## Phase 3: New Functionality

### 3.1 Javadoc/Sources Jar Scaffolding

Add `autoConfigureJars` option (default: `true`):

```kotlin
mavenCentralPortal {
    autoConfigureJars = true // default
}
```

**Behavior when enabled:**
1. Register `javadocJar` task — empty javadoc jar by default, or Dokka output if Dokka plugin is detected
2. Register `sourcesJar` task — includes main source sets
3. Attach both jars to all `MavenPublication` instances

**Safety rules:**
- If user already has `javadocJar` or `sourcesJar` tasks registered — do not overwrite, use existing ones
- Dokka detection: check if `org.jetbrains.dokka` plugin is applied, if so use its output for javadoc jar content
- KMP support: correctly handle Kotlin Multiplatform source sets

**Out of scope:** Auto-applying Dokka as a dependency. Only detect if already present.

---

## Phase 4: Tests (Cross-Cutting)

### 4.1 New Unit Tests

| Target | What to test |
|--------|-------------|
| `CreateChecksumTask` | MD5/SHA1/SHA256/SHA512 generation, signature file exclusion, output file naming |
| `CredentialMapping` | Mapping from Gradle properties to `Credentials` model, validation errors |
| `PublicationMapping` | Mapping from Gradle publications to `PublicationInfo` model |
| POM helpers | License presets return correct data, `fromGithub()` generates correct URLs, multiple developers/licenses |
| Retry config | New properties have correct defaults, values propagate to task inputs |

### 4.2 New Functional Tests (Gradle TestKit)

| Target | What to test |
|--------|-------------|
| Configurator classes | `RootProjectConfigurator`, `SubprojectConfigurator`, `ZipDeploymentConfigurator` — correct task registration, extension wiring |
| POM defaults | Defaults apply to publications, standard `pom {}` overrides our defaults, validation catches missing required fields |
| Jar scaffolding | Jars created and attached at `autoConfigureJars = true`, existing tasks not overwritten, disabled at `false`, Dokka detection |
| Retry config | Custom values applied to API client behavior |

### 4.3 Out of Scope

- Tests on `NoOpMavenCentralApiClient` — trivial stub
- Tests on enums/data classes without logic (`HttpStatus`, `DeploymentId`)
- Tests on `ProjectUtils`, `TaskRegistration` — thin Gradle API wrappers, covered by functional tests

---

## Non-Goals

- CHANGELOG automation
- Legacy Nexus API support
- Custom retry predicates or failure hooks
- Auto-detection of POM data from git remote
- Auto-applying Dokka as a dependency
- Full replacement of standard Gradle `pom {}` block
