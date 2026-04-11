package io.github.zenhelix.gradle.plugin.extension

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
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
        assertThat(result).isInstanceOf(Success::class.java)
        val credentials = (result as Success).value
        assertThat(credentials).isInstanceOf(Credentials.BearerTokenCredentials::class.java)
        assertThat((credentials as Credentials.BearerTokenCredentials).token).isEqualTo("my-token")
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
        assertThat(result).isInstanceOf(Success::class.java)
        val credentials = (result as Success).value
        assertThat(credentials).isInstanceOf(Credentials.UsernamePasswordCredentials::class.java)
        val creds = credentials as Credentials.UsernamePasswordCredentials
        assertThat(creds.username).isEqualTo("user")
        assertThat(creds.password).isEqualTo("pass")
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
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isInstanceOf(ValidationError.AmbiguousCredentials::class.java)
        assertThat(error.message).contains("Both 'bearer' and 'usernamePassword'")
    }

    @Test
    fun `no block configured returns Failure with NoCredentials`() {
        val extension = createExtension()

        val result = project.mapCredentials(extension).get()
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isInstanceOf(ValidationError.NoCredentials::class.java)
        assertThat(error.message).contains("No credentials configured")
    }

    @Test
    fun `bearer block without token returns Failure with MissingCredential`() {
        val extension = createExtension()
        extension.credentials {
            bearer { /* token not set */ }
        }

        val result = project.mapCredentials(extension).get()
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isInstanceOf(ValidationError.MissingCredential::class.java)
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
        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat((result as Failure).error.message).contains("Username is not set")
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
        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat((result as Failure).error.message).contains("Password is not set")
    }
}
