#!/usr/bin/env kotlin

// github-workflows-kt
@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:3.7.0")
@file:Repository("https://bindings.krzeminski.it")

// Actions
@file:DependsOn("actions:labeler:v6")

import io.github.typesafegithub.workflows.actions.actions.Labeler
import io.github.typesafegithub.workflows.domain.Mode.Write
import io.github.typesafegithub.workflows.domain.Permission.Contents
import io.github.typesafegithub.workflows.domain.Permission.PullRequests
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.PullRequest
import io.github.typesafegithub.workflows.dsl.expressions.expr
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.yaml.ConsistencyCheckJobConfig.Disabled

check(KotlinVersion.CURRENT.isAtLeast(2, 1, 0)) {
    "This script requires Kotlin 2.1.0 or later. Current: ${KotlinVersion.CURRENT}"
}

workflow(
    name = "PR Labeler",
    on = listOf(PullRequest()),
    permissions = mapOf(
        Contents to Write,
        PullRequests to Write
    ),
    sourceFile = __FILE__,
    targetFileName = "labeler.yml",
    consistencyCheckJobConfig = Disabled
) {
    job(id = "label", name = "Label PR", runsOn = UbuntuLatest) {
        uses(
            name = "Label PR based on file paths",
            action = Labeler(repoToken = expr { secrets.GITHUB_TOKEN }, syncLabels = true)
        )
    }
}
