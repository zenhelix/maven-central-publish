package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.PublishingType
import io.github.zenhelix.gradle.plugin.task.ArtifactFileInfo
import io.github.zenhelix.gradle.plugin.task.ArtifactInfo
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.GAV
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.listProperty

internal fun Project.mapCredentials(
    extension: MavenCentralUploaderExtension
): Provider<Outcome<Credentials, ValidationError>> = provider {
    val creds = extension.credentials
    when {
        creds.isBearerConfigured && creds.isUsernamePasswordConfigured -> {
            Failure(ValidationError.AmbiguousCredentials(
                "Both 'bearer' and 'usernamePassword' credential blocks are configured. " +
                    "Use exactly one: credentials { bearer { ... } } or credentials { usernamePassword { ... } }"
            ))
        }
        creds.isBearerConfigured -> {
            val token = creds.bearer.token.orNull
            if (token != null) {
                Success(Credentials.BearerTokenCredentials(token))
            } else {
                Failure(ValidationError.MissingCredential("Bearer token is not set. Configure: credentials { bearer { token.set(\"...\") } }"))
            }
        }
        creds.isUsernamePasswordConfigured -> {
            val username = creds.usernamePassword.username.orNull
            val password = creds.usernamePassword.password.orNull
            when {
                username == null -> Failure(ValidationError.MissingCredential("Username is not set. Configure: credentials { usernamePassword { username.set(\"...\") } }"))
                password == null -> Failure(ValidationError.MissingCredential("Password is not set. Configure: credentials { usernamePassword { password.set(\"...\") } }"))
                else -> Success(Credentials.UsernamePasswordCredentials(username, password))
            }
        }
        else -> {
            Failure(ValidationError.NoCredentials)
        }
    }
}

internal fun MavenPublicationInternal.mapModel(
    project: Project,
    checksumTask: TaskProvider<CreateChecksumTask>
): PublicationInfo = PublicationInfo(
    projectPath = project.path,
    gav = GAV.of(this),
    publicationName = this.name,
    artifacts = project.objects.listProperty<ArtifactInfo>().apply {
        convention(project.provider {
            this@mapModel.publishableArtifacts.map {
                ArtifactInfo(artifact = ArtifactFileInfo.of(it), gav = GAV.of(this@mapModel))
            }
        })
    },
    checksumFiles = checksumTask.flatMap { it.checksumFiles }
)

internal fun PublishingType.mapModel(): io.github.zenhelix.gradle.plugin.client.model.PublishingType = when (this) {
    PublishingType.AUTOMATIC -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.AUTOMATIC
    PublishingType.USER_MANAGED -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.USER_MANAGED
}
