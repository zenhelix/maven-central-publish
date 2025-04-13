package io.github.zenhelix.gradle.plugin.extension

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import java.time.Duration
import javax.inject.Inject

public open class MavenCentralUploaderExtension @Inject constructor(objects: ObjectFactory) {

    public val baseUrl: Property<String> = objects.property<String>().convention(DEFAULT_CENTRAL_MAVEN_PORTAL_BASE_URL)

    public val credentials: MavenCentralUploaderCredentialExtension = objects.newInstance<MavenCentralUploaderCredentialExtension>()
    public fun credentials(configure: Action<MavenCentralUploaderCredentialExtension>) {
        configure.execute(credentials)
    }

    public val publishingType: Property<PublishingType> = objects.property<PublishingType>().convention(PublishingType.AUTOMATIC)

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
    public val username: Property<String> = objects.property<String>()
    public val password: Property<String> = objects.property<String>()
}

public open class UploaderSettingsExtension @Inject constructor(objects: ObjectFactory) {

    public val aggregatePublications: Property<Boolean> = objects.property<Boolean>().convention(false)

    public val maxRetriesStatusCheck: Property<Int> = objects.property<Int>().convention(DEFAULT_MAX_RETRIES)
    public val delayRetriesStatusCheck: Property<Duration> = objects.property<Duration>().convention(DEFAULT_DELAY_RETRIES)

    public companion object {
        public const val DEFAULT_MAX_RETRIES: Int = 5
        public val DEFAULT_DELAY_RETRIES: Duration = Duration.ofSeconds(2)
    }
}
