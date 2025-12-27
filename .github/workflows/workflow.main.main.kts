#!/usr/bin/env kotlin

// github-workflows-kt
@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:3.7.0")
@file:Repository("https://bindings.krzeminski.it")

// Actions
@file:DependsOn("actions:checkout:v6")
@file:DependsOn("actions:setup-java:v5")
@file:DependsOn("gradle:actions__setup-gradle:v5")
@file:DependsOn("peter-murray:workflow-application-token-action:v4")
@file:DependsOn("anothrNick:github-tag-action:v1")

import Branches.MAIN_BRANCH_NAME
import Environment.DEFAULT_BUMP_ENV
import Environment.GITHUB_TOKEN_ENV
import Environment.RELEASE_BRANCHES_ENV
import Environment.TAG_CONTEXT_ENV
import Environment.TAG_PREFIX_ENV
import Secrets.ZENHELIX_COMMITER_APP_ID
import Secrets.ZENHELIX_COMMITER_APP_PRIVATE_KEY
import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.actions.actions.Checkout.FetchDepth
import io.github.typesafegithub.workflows.actions.actions.SetupJava
import io.github.typesafegithub.workflows.actions.actions.SetupJava.Distribution.Temurin
import io.github.typesafegithub.workflows.actions.anothrnick.GithubTagAction
import io.github.typesafegithub.workflows.actions.gradle.ActionsSetupGradle
import io.github.typesafegithub.workflows.actions.petermurray.WorkflowApplicationTokenAction_Untyped
import io.github.typesafegithub.workflows.domain.Mode.Write
import io.github.typesafegithub.workflows.domain.Permission.Contents
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.dsl.expressions.contexts.SecretsContext
import io.github.typesafegithub.workflows.dsl.expressions.expr
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.yaml.ConsistencyCheckJobConfig.Disabled

check(KotlinVersion.CURRENT.isAtLeast(2, 1, 0)) {
    "This script requires Kotlin 2.1.0 or later. Current: ${KotlinVersion.CURRENT}"
}

object Secrets {
    val SecretsContext.ZENHELIX_COMMITER_APP_ID by SecretsContext.propertyToExprPath
    val SecretsContext.ZENHELIX_COMMITER_APP_PRIVATE_KEY by SecretsContext.propertyToExprPath
}

object Environment {
    const val GITHUB_TOKEN_ENV = "GITHUB_TOKEN"

    const val TAG_PREFIX_ENV = "TAG_PREFIX"
    const val DEFAULT_BUMP_ENV = "DEFAULT_BUMP"
    const val RELEASE_BRANCHES_ENV = "RELEASE_BRANCHES"
    const val TAG_CONTEXT_ENV = "TAG_CONTEXT"
}

object Branches {
    const val MAIN_BRANCH_NAME = "main"
}

workflow(
    name = "Create Tag",
    on = listOf(Push(branches = listOf(MAIN_BRANCH_NAME, "[0-9]+.x"))),
    permissions = mapOf(Contents to Write),
    sourceFile = __FILE__,
    targetFileName = "build-on-main.yml",
    consistencyCheckJobConfig = Disabled
) {
    job(id = "create_release_tag", name = "Create Release Tag", runsOn = UbuntuLatest) {
        uses(name = "Check out", action = Checkout(fetchDepth = FetchDepth.Value(0)))
        uses(name = "Set up Java", action = SetupJava(javaVersion = "17", distribution = Temurin))
        uses(name = "Setup Gradle", action = ActionsSetupGradle(gradleHomeCacheCleanup = true))
        run(name = "Check", command = "./gradlew check")
        val token = uses(
            name = "Get Token", action = WorkflowApplicationTokenAction_Untyped(
                applicationId_Untyped = expr { secrets.ZENHELIX_COMMITER_APP_ID },
                applicationPrivateKey_Untyped = expr { secrets.ZENHELIX_COMMITER_APP_PRIVATE_KEY }
            )
        ).outputs.token
        uses(
            name = "Bump version and push tag", action = GithubTagAction(),
            env = mapOf(
                GITHUB_TOKEN_ENV to expr { token },
                TAG_PREFIX_ENV to "",
                DEFAULT_BUMP_ENV to "patch",
                RELEASE_BRANCHES_ENV to "main,[0-9]+\\.x",
                TAG_CONTEXT_ENV to "branch"
            )
        )
    }
}
