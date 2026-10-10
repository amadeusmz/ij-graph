import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.ijgraph"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// -PlocalIde=<IDE install dir> builds and verifies against an installed IDE instead of downloading one
val localIde: String? = providers.gradleProperty("localIde").orNull

dependencies {
    intellijPlatform {
        if (localIde != null) local(localIde) else intellijIdea(providers.gradleProperty("platformVersion"))
        bundledModules("intellij.platform.vcs.log", "intellij.platform.vcs.log.graph", "intellij.platform.vcs.log.impl")
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(25)
    // no compatibility bridges for interface default methods (VcsLogCellRenderer has deprecated ones)
    compilerOptions { jvmDefault = JvmDefaultMode.NO_COMPATIBILITY }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262.10968" // 2026.2.3
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides { if (localIde != null) local(file(localIde)) else recommended() }
        // hiding the built-in graph needs internal VCS Log API by design, only real incompatibilities fail the build
        failureLevel = listOf(FailureLevel.COMPATIBILITY_PROBLEMS, FailureLevel.INVALID_PLUGIN)
    }
}
