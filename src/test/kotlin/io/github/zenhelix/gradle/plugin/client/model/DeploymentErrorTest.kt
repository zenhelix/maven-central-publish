package io.github.zenhelix.gradle.plugin.client.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeploymentErrorTest {

    @Test
    fun `DeploymentFailed with droppable state is droppable`() {
        val error = DeploymentError.DeploymentFailed(DeploymentStateType.FAILED, null)
        assertThat(error.isDroppable).isTrue()
    }

    @Test
    fun `DeploymentFailed with PUBLISHING state is not droppable`() {
        val error = DeploymentError.DeploymentFailed(DeploymentStateType.PUBLISHING, null)
        assertThat(error.isDroppable).isFalse()
    }

    @Test
    fun `Timeout with droppable state is droppable`() {
        val error = DeploymentError.Timeout(DeploymentStateType.PENDING, 20)
        assertThat(error.isDroppable).isTrue()
    }

    @Test
    fun `Timeout with PUBLISHING state is not droppable`() {
        val error = DeploymentError.Timeout(DeploymentStateType.PUBLISHING, 20)
        assertThat(error.isDroppable).isFalse()
    }

    @Test
    fun `StatusCheckFailed is always droppable`() {
        val error = DeploymentError.StatusCheckFailed(503, "Service Unavailable")
        assertThat(error.isDroppable).isTrue()
    }

    @Test
    fun `StatusCheckUnexpected is always droppable`() {
        val error = DeploymentError.StatusCheckUnexpected(RuntimeException("network error"))
        assertThat(error.isDroppable).isTrue()
    }

    @Test
    fun `UploadFailed is not droppable`() {
        val error = DeploymentError.UploadFailed(400, "Bad Request")
        assertThat(error.isDroppable).isFalse()
    }

    @Test
    fun `UploadUnexpected is not droppable`() {
        val error = DeploymentError.UploadUnexpected(RuntimeException("timeout"))
        assertThat(error.isDroppable).isFalse()
    }

    @Test
    fun `toGradleException preserves cause for UploadUnexpected`() {
        val cause = RuntimeException("timeout")
        val error = DeploymentError.UploadUnexpected(cause)
        val gradleEx = error.toGradleException()
        assertThat(gradleEx.cause).isSameAs(cause)
        assertThat(gradleEx.message).isEqualTo("Unexpected error during bundle upload")
    }

    @Test
    fun `toGradleException has no cause for UploadFailed`() {
        val error = DeploymentError.UploadFailed(400, "Bad Request")
        val gradleEx = error.toGradleException()
        assertThat(gradleEx.cause).isNull()
        assertThat(gradleEx.message).isEqualTo("Failed to upload bundle: HTTP 400")
    }
}
