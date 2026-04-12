package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.isDroppable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class DeploymentStateDroppableTest {

    @ParameterizedTest
    @EnumSource(value = DeploymentStateType::class, names = ["PENDING", "VALIDATING", "VALIDATED", "FAILED", "UNKNOWN"])
    fun `droppable states should return true`(state: DeploymentStateType) {
        assertThat(state.isDroppable).isTrue()
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentStateType::class, names = ["PUBLISHING", "PUBLISHED"])
    fun `non-droppable states should return false`(state: DeploymentStateType) {
        assertThat(state.isDroppable).isFalse()
    }
}
