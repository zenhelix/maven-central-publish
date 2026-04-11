package test

import java.io.File
import java.nio.file.Path
import test.utils.PgpUtils.generatePgpKeyPair

internal fun File.settingsGradleFile() = File(this, "settings.gradle.kts")
internal fun File.buildGradleFile(module: String? = null) =
    File(this, "${module?.let { "$it/" } ?: ""}build.gradle.kts").also {
        it.parentFile.mkdirs()
    }

internal fun settings(root: String, vararg subprojects: String) = """
rootProject.name = "$root"

${
    if (subprojects.isNotEmpty()) {
        "include(${subprojects.joinToString(separator = ", ", transform = { "\":$it\"" })})"
    } else {
        ""
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        mavenLocal()
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenLocal()
    }
}
""".trimIndent()


fun signing() = """
signing {
    useInMemoryPgpKeys(""${'"'}${generatePgpKeyPair("stub-password")}""${'"'}, "stub-password")
    sign(publishing.publications)
}
"""

internal const val pom = """
publishing.publications.withType<MavenPublication> {            
    pom {
        description = "stub description"
        url = "https://stub.stub"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        scm {
            connection = "scm:git:git://stub.stub.git"
            developerConnection = "scm:git:ssh://stub.stub.git"
            url = "https://stub.stub"
        }
        developers {
            developer {
                id = "stub"
                name = "Stub Stub"
                email = "stub@stub.stub"
            }
        }
    }
}
"""

internal fun File.createJavaMainClass(module: String? = null): File = File(
    this, (if (module != null) "$module/" else "") + "src/main/java/test/Test.java"
).apply {
    parentFile.mkdirs()
    writeText(
        // language="JAVA"
        """
            package test;
            public class Test { public static void test() {} }
        """.trimIndent()
    )
}

internal fun File.createKotlinCommonMainClass(module: String? = null): File = File(
    this,
    (if (module != null) "$module/" else "") + "src/commonMain/kotlin/test/TestFile.kt"
).apply {
    parentFile.mkdirs()
    // language="kotlin"
    writeText(
        """
            package test
            
            fun generate() {}
            """.trimIndent()
    )
}

internal fun group(group: String = "test.zenhelix", version: String = "1.0.0") = """
allprojects {
    group = "$group"
    version = "$version"
}
""".trimIndent()


internal fun mavenCentralPortal() = """
mavenCentralPortal {
    baseUrl = "http://test" 
    credentials {
        username = "stub"
        password = "stub"
    }
}
""".trimIndent()

internal fun File.moduleBundleFile(
    gradleModuleName: String? = null, moduleName: String, version: String,
    publicationName: String? = null
): Path = this
    .distributionsDirectory(gradleModuleName = gradleModuleName)
    .moduleBundlePath(moduleName = moduleName, version = version, publicationName = publicationName)

internal fun Path.moduleBundlePath(
    moduleName: String, version: String, publicationName: String? = null
): Path = this.resolve("$moduleName-${publicationName?.let { "$it-" } ?: ""}$version.zip")

internal fun File.buildDirectory(
    gradleModuleName: String? = null, buildDirectory: String = "build"
): Path = this.toPath().let { path -> gradleModuleName?.let { path.resolve(it) } ?: path }.resolve(buildDirectory)


internal fun File.distributionsDirectory(
    gradleModuleName: String? = null,
    buildDirectory: String = "build", distributionsDirectory: String = "distributions"
): Path = this.buildDirectory(gradleModuleName, buildDirectory).resolve(distributionsDirectory)