package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import org.assertj.core.api.Assertions.assertThat
import org.gradle.kotlin.dsl.newInstance
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class CredentialMappingTest {

    private val project = ProjectBuilder.builder().build()

    private fun createExtension(): MavenCentralUploaderExtension =
        project.objects.newInstance<MavenCentralUploaderExtension>()

    @Test
    fun `bearer token credentials are mapped correctly`() {
        val extension = createExtension()
        extension.credentials {
            bearer { token.set("test-token") }
        }

        val result = project.mapCredentials(extension).get()
        val credentials = assertSuccess<Credentials.BearerTokenCredentials>(result)
        assertThat(credentials.token).isEqualTo("test-token")
    }

    @Test
    fun `username password credentials are mapped correctly`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                username.set("test-user")
                password.set("test-pass")
            }
        }

        val result = project.mapCredentials(extension).get()
        val credentials = assertSuccess<Credentials.UsernamePasswordCredentials>(result)
        assertThat(credentials.username).isEqualTo("test-user")
        assertThat(credentials.password).isEqualTo("test-pass")
    }

    @Test
    fun `no credentials returns NoCredentials error`() {
        val extension = createExtension()

        val result = project.mapCredentials(extension).get()
        assertFailure<ValidationError.NoCredentials>(result)
    }

    @Test
    fun `both credentials configured returns AmbiguousCredentials error`() {
        val extension = createExtension()
        extension.credentials {
            bearer { token.set("test-token") }
            usernamePassword {
                username.set("test-user")
                password.set("test-pass")
            }
        }

        val result = project.mapCredentials(extension).get()
        val error = assertFailure<ValidationError.AmbiguousCredentials>(result)
        assertThat(error.message).contains("Both 'bearer' and 'usernamePassword'")
    }

    @Test
    fun `bearer configured but token missing returns MissingCredential`() {
        val extension = createExtension()
        extension.credentials {
            bearer { /* token not set */ }
        }

        val result = project.mapCredentials(extension).get()
        val error = assertFailure<ValidationError.MissingCredential>(result)
        assertThat(error.message).contains("Bearer token is not set")
    }
}
