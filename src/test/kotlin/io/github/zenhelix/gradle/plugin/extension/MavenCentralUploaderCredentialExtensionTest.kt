package io.github.zenhelix.gradle.plugin.extension

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.utils.mapCredentials
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
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

        val credentials = project.mapCredentials(extension).get()
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

        val credentials = project.mapCredentials(extension).get()
        assertThat(credentials).isInstanceOf(Credentials.UsernamePasswordCredentials::class.java)
        val creds = credentials as Credentials.UsernamePasswordCredentials
        assertThat(creds.username).isEqualTo("user")
        assertThat(creds.password).isEqualTo("pass")
    }

    @Test
    fun `both blocks configured throws GradleException`() {
        val extension = createExtension()
        extension.credentials {
            bearer { token.set("my-token") }
            usernamePassword {
                username.set("user")
                password.set("pass")
            }
        }

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("Both 'bearer' and 'usernamePassword'")
    }

    @Test
    fun `no block configured throws GradleException`() {
        val extension = createExtension()

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("No credentials configured")
    }

    @Test
    fun `bearer block without token throws GradleException`() {
        val extension = createExtension()
        extension.credentials {
            bearer { /* token not set */ }
        }

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("Bearer token is not set")
    }

    @Test
    fun `usernamePassword block without username throws GradleException`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                password.set("pass")
            }
        }

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("Username is not set")
    }

    @Test
    fun `usernamePassword block without password throws GradleException`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                username.set("user")
            }
        }

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("Password is not set")
    }
}
