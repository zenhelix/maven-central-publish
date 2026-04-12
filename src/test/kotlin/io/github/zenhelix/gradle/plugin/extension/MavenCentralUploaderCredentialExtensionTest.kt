package io.github.zenhelix.gradle.plugin.extension

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.utils.assertFailure
import io.github.zenhelix.gradle.plugin.utils.assertSuccess
import io.github.zenhelix.gradle.plugin.utils.mapCredentials
import org.assertj.core.api.Assertions.assertThat
import org.gradle.kotlin.dsl.newInstance
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class MavenCentralUploaderCredentialExtensionTest {

    private val project = ProjectBuilder.builder().build()

    private fun createExtension(): MavenCentralUploaderExtension =
        project.objects.newInstance<MavenCentralUploaderExtension>()

    @Test
    fun `bearer block creates BearerTokenCredentials`() {
        val extension = createExtension()
        extension.credentials {
            bearer { token.set("my-token") }
        }

        val result = project.mapCredentials(extension).get()
        val credentials = assertSuccess<Credentials.BearerTokenCredentials>(result)
        assertThat(credentials.token).isEqualTo("my-token")
    }

    @Test
    fun `usernamePassword block creates UsernamePasswordCredentials`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                username.set("user")
                password.set("pass")
            }
        }

        val result = project.mapCredentials(extension).get()
        val credentials = assertSuccess<Credentials.UsernamePasswordCredentials>(result)
        assertThat(credentials.username).isEqualTo("user")
        assertThat(credentials.password).isEqualTo("pass")
    }

    @Test
    fun `both blocks configured returns Failure with AmbiguousCredentials`() {
        val extension = createExtension()
        extension.credentials {
            bearer { token.set("my-token") }
            usernamePassword {
                username.set("user")
                password.set("pass")
            }
        }

        val result = project.mapCredentials(extension).get()
        val error = assertFailure<ValidationError.AmbiguousCredentials>(result)
        assertThat(error.message).contains("Both 'bearer' and 'usernamePassword'")
    }

    @Test
    fun `no block configured returns Failure with NoCredentials`() {
        val extension = createExtension()

        val result = project.mapCredentials(extension).get()
        val error = assertFailure<ValidationError.NoCredentials>(result)
        assertThat(error.message).contains("No credentials configured")
    }

    @Test
    fun `bearer block without token returns Failure with MissingCredential`() {
        val extension = createExtension()
        extension.credentials {
            bearer { /* token not set */ }
        }

        val result = project.mapCredentials(extension).get()
        val error = assertFailure<ValidationError.MissingCredential>(result)
        assertThat(error.message).contains("Bearer token is not set")
    }

    @Test
    fun `usernamePassword block without username returns Failure with MissingCredential`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                password.set("pass")
            }
        }

        val result = project.mapCredentials(extension).get()
        val error = assertFailure<ValidationError.MissingCredential>(result)
        assertThat(error.message).contains("Username is not set")
    }

    @Test
    fun `usernamePassword block without password returns Failure with MissingCredential`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                username.set("user")
            }
        }

        val result = project.mapCredentials(extension).get()
        val error = assertFailure<ValidationError.MissingCredential>(result)
        assertThat(error.message).contains("Password is not set")
    }
}
