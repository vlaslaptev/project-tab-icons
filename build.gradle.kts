import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.vlas.projecttabicons"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        val localPlatformPath = providers.gradleProperty("localPlatformPath")
        if (localPlatformPath.isPresent) {
            local(localPlatformPath.get())
        } else {
            intellijIdea(providers.gradleProperty("platformVersion").get())
        }
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    // Настройки объявлены в plugin.xml; индексация searchable options включена.
    buildSearchableOptions = true

    pluginConfiguration {
        version = project.version.toString()
        description = providers.fileContents(
            layout.projectDirectory.file("docs/marketplace/description.html")
        ).asText
        changeNotes = providers.fileContents(
            layout.projectDirectory.file("docs/marketplace/change-notes.html")
        ).asText
        ideaVersion {
            sinceBuild = "262.10968.63"
            // strict-until-build находится в исходном plugin.xml.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            current()
        }
        // Стандартные уровни ошибок сохранены: Internal API Usage не скрывается.
    }

    signing {
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }
}
