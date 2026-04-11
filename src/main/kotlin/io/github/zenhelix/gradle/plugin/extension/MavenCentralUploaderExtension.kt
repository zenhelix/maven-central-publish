package io.github.zenhelix.gradle.plugin.extension

import io.github.zenhelix.gradle.plugin.extension.PublishingType.AUTOMATIC
import java.time.Duration
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

public open class MavenCentralUploaderExtension @Inject constructor(objects: ObjectFactory) {

    public val baseUrl: Property<String> = objects.property<String>().convention(DEFAULT_CENTRAL_MAVEN_PORTAL_BASE_URL)

    public val credentials: MavenCentralUploaderCredentialExtension =
        objects.newInstance<MavenCentralUploaderCredentialExtension>()

    public fun credentials(configure: Action<MavenCentralUploaderCredentialExtension>) {
        configure.execute(credentials)
    }

    public val publishingType: Property<PublishingType> = objects.property<PublishingType>().convention(AUTOMATIC)

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

public open class MavenCentralUploaderCredentialExtension @Inject constructor(objects: ObjectFactory) {

    // Tracks whether each block was entered (not whether properties are set).
    // This allows better error messages: `bearer { }` without token.set() reports
    // "Bearer token is not set" rather than the generic "No credentials configured".
    private var bearerConfigured: Boolean = false
    private var usernamePasswordConfigured: Boolean = false

    public val bearer: BearerCredentialExtension = objects.newInstance<BearerCredentialExtension>()
    public val usernamePassword: UsernamePasswordCredentialExtension =
        objects.newInstance<UsernamePasswordCredentialExtension>()

    public fun bearer(configure: Action<BearerCredentialExtension>) {
        bearerConfigured = true
        configure.execute(bearer)
    }

    public fun usernamePassword(configure: Action<UsernamePasswordCredentialExtension>) {
        usernamePasswordConfigured = true
        configure.execute(usernamePassword)
    }

    public val isBearerConfigured: Boolean get() = bearerConfigured
    public val isUsernamePasswordConfigured: Boolean get() = usernamePasswordConfigured
}

public open class BearerCredentialExtension @Inject constructor(objects: ObjectFactory) {
    public val token: Property<String> = objects.property<String>()
}

public open class UsernamePasswordCredentialExtension @Inject constructor(objects: ObjectFactory) {
    public val username: Property<String> = objects.property<String>()
    public val password: Property<String> = objects.property<String>()
}

public open class UploaderSettingsExtension @Inject constructor(objects: ObjectFactory) {

    public val maxStatusChecks: Property<Int> = objects.property<Int>().convention(DEFAULT_MAX_STATUS_CHECKS)
    public val statusCheckDelay: Property<Duration> =
        objects.property<Duration>().convention(DEFAULT_STATUS_CHECK_DELAY)
    public val maxBundleSize: Property<Long> = objects.property<Long>().convention(DEFAULT_MAX_BUNDLE_SIZE)

    public companion object {
        public const val DEFAULT_MAX_STATUS_CHECKS: Int = 20
        public val DEFAULT_STATUS_CHECK_DELAY: Duration = Duration.ofSeconds(10)
        public const val DEFAULT_MAX_BUNDLE_SIZE: Long = 256L * 1024L * 1024L // 256 MB
    }
}
