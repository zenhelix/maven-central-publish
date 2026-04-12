package io.github.zenhelix.gradle.plugin.extension

import io.github.zenhelix.gradle.plugin.extension.PublishingMode.AUTOMATIC
import java.time.Duration
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

/**
 * Main configuration extension for the Maven Central publish plugin, registered as the
 * `mavenCentralPortal { }` DSL block in your build script.
 *
 * Example usage:
 * ```kotlin
 * mavenCentralPortal {
 *     baseUrl = "https://central.sonatype.com"
 *     publishingType = PublishingMode.AUTOMATIC
 *     credentials {
 *         bearer { token = providers.environmentVariable("MAVEN_TOKEN") }
 *     }
 *     uploader {
 *         maxStatusChecks = 30
 *         statusCheckDelay = Duration.ofSeconds(15)
 *         maxBundleSize = 512.megabytes
 *     }
 * }
 * ```
 *
 * @see PublishingMode
 * @see MavenCentralUploaderCredentialExtension
 * @see UploaderSettingsExtension
 */
public open class MavenCentralUploaderExtension @Inject constructor(objects: ObjectFactory) {

    public val baseUrl: Property<String> = objects.property<String>().convention(DEFAULT_CENTRAL_MAVEN_PORTAL_BASE_URL)

    public val credentials: MavenCentralUploaderCredentialExtension = objects.newInstance<MavenCentralUploaderCredentialExtension>()

    public fun credentials(configure: Action<MavenCentralUploaderCredentialExtension>) {
        configure.execute(credentials)
    }

    public val publishingType: Property<PublishingMode> = objects.property<PublishingMode>().convention(AUTOMATIC)

    public val deploymentName: Property<String> = objects.property<String>()

    public val uploader: UploaderSettingsExtension = objects.newInstance<UploaderSettingsExtension>()
    public fun uploader(configure: Action<UploaderSettingsExtension>) {
        configure.execute(uploader)
    }

    public companion object {
        public const val MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME: String = "mavenCentralPortal"

        public const val DEFAULT_CENTRAL_MAVEN_PORTAL_BASE_URL: String = "https://central.sonatype.com"
    }
}

/**
 * Credential configuration for authenticating with the Maven Central Portal API.
 *
 * Exactly one credential mode must be configured — either [bearer] or [usernamePassword].
 * Configuring both will result in a validation error at publish time.
 *
 * Bearer token example:
 * ```kotlin
 * credentials {
 *     bearer { token = providers.environmentVariable("MAVEN_TOKEN") }
 * }
 * ```
 *
 * Username/password example:
 * ```kotlin
 * credentials {
 *     usernamePassword {
 *         username = providers.environmentVariable("MAVEN_USERNAME")
 *         password = providers.environmentVariable("MAVEN_PASSWORD")
 *     }
 * }
 * ```
 */
public open class MavenCentralUploaderCredentialExtension @Inject constructor(objects: ObjectFactory) {

    private sealed interface CredentialMode {
        data object None : CredentialMode
        data object Bearer : CredentialMode
        data object UsernamePassword : CredentialMode
        data object Both : CredentialMode
    }

    private var mode: CredentialMode = CredentialMode.None

    public val bearer: BearerCredentialExtension = objects.newInstance<BearerCredentialExtension>()
    public val usernamePassword: UsernamePasswordCredentialExtension =
        objects.newInstance<UsernamePasswordCredentialExtension>()

    public fun bearer(configure: Action<BearerCredentialExtension>) {
        mode = when (mode) {
            CredentialMode.None -> CredentialMode.Bearer
            CredentialMode.UsernamePassword -> CredentialMode.Both
            else -> mode
        }
        configure.execute(bearer)
    }

    public fun usernamePassword(configure: Action<UsernamePasswordCredentialExtension>) {
        mode = when (mode) {
            CredentialMode.None -> CredentialMode.UsernamePassword
            CredentialMode.Bearer -> CredentialMode.Both
            else -> mode
        }
        configure.execute(usernamePassword)
    }

    public val isBearerConfigured: Boolean get() = mode == CredentialMode.Bearer || mode == CredentialMode.Both
    public val isUsernamePasswordConfigured: Boolean get() = mode == CredentialMode.UsernamePassword || mode == CredentialMode.Both
}

/**
 * Bearer token credential for authenticating with the Maven Central Portal.
 *
 * Generate a token at https://central.sonatype.com/account (User Token section).
 */
public open class BearerCredentialExtension @Inject constructor(objects: ObjectFactory) {
    public val token: Property<String> = objects.property<String>()
}

/**
 * Username/password credential for authenticating with the Maven Central Portal.
 *
 * The username and password are Base64-encoded at runtime to produce the HTTP Basic
 * `Authorization` header value.
 */
public open class UsernamePasswordCredentialExtension @Inject constructor(objects: ObjectFactory) {
    public val username: Property<String> = objects.property<String>()
    public val password: Property<String> = objects.property<String>()
}

/**
 * Fine-grained settings that control the upload and status-polling behaviour.
 *
 * Example:
 * ```kotlin
 * uploader {
 *     maxStatusChecks = 30
 *     statusCheckDelay = Duration.ofSeconds(15)
 *     maxBundleSize = 512.megabytes   // or e.g. 1.gigabytes
 * }
 * ```
 *
 * @property maxStatusChecks Maximum number of deployment-status polls before the task fails
 * with a timeout error. Default: 20.
 * @property statusCheckDelay Delay between consecutive status polls. Default: 10 seconds.
 * @property maxBundleSize Maximum size (in bytes) of a single upload bundle. When a bundle
 * exceeds this limit it is automatically split into smaller chunks. Default: 256 MB.
 * Use the [Int.megabytes] or [Int.gigabytes] extension properties for readable values.
 */
public open class UploaderSettingsExtension @Inject constructor(objects: ObjectFactory) {

    public val maxStatusChecks: Property<Int> = objects.property<Int>().convention(DEFAULT_MAX_STATUS_CHECKS)
    public val statusCheckDelay: Property<Duration> =
        objects.property<Duration>().convention(DEFAULT_STATUS_CHECK_DELAY)
    public val maxBundleSize: Property<Long> = objects.property<Long>().convention(DEFAULT_MAX_BUNDLE_SIZE)

    public companion object {
        public const val DEFAULT_MAX_STATUS_CHECKS: Int = 20
        public val DEFAULT_STATUS_CHECK_DELAY: Duration = Duration.ofSeconds(10)
        public val DEFAULT_MAX_BUNDLE_SIZE: Long = 256.megabytes
    }
}
