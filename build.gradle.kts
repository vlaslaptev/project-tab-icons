import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import java.time.Duration

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

// Startup activity Ultimate требует полноценного контекста IDE, которого нет в light fixture SDK 262.
tasks.named<PrepareSandboxTask>("prepareTestSandbox") {
    disabledPlugins.add("com.intellij.modules.ultimate")
}

// Отдельный тестовый плагин, не включаемый в устанавливаемый ZIP продукта.
val nativeProbe = providers.gradleProperty("nativeProbe").isPresent
val nativeProbeDirectory = layout.buildDirectory.dir("native-probe")
val prepareNativeFixtures = tasks.register("prepareNativeFixtures") {
    val output = nativeProbeDirectory
    doLast {
        val root = output.get().asFile
        root.mkdirs()
        root.resolve("results.json").delete()
        listOf("one" to "#FF0000", "two" to "#00FF00", "three" to "#0000FF").forEach { (name, color) ->
            val directory = root.resolve("projects/$name/.idea").apply { mkdirs() }
            directory.resolve(".name").writeText(if (name == "three") "Third project" else "Same name")
            directory.resolve("icon.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path fill=\"$color\" d=\"M0 0h24v24H0z\"/></svg>")
            directory.parentFile.resolve("scratch.txt").writeText("Native integration fixture\n")
        }
    }
}
val nativeProbeJar = tasks.register<Jar>("nativeProbeJar") {
    dependsOn(tasks.compileTestKotlin)
    archiveFileName = "native-probe.jar"
    from(sourceSets.test.get().output) { include("dev/vlas/projecttabicons/NativeTabsProbe*") }
    from("src/nativeTest/resources")
}
tasks.withType<PrepareSandboxTask>().configureEach {
    if (nativeProbe && !name.contains("test", ignoreCase = true)) {
        disabledPlugins.set(emptySet())
        from(nativeProbeJar) { into("native-probe/lib") }
    }
}
tasks.named<RunIdeTask>("runIde") {
    composeHotReload = false
    composeHotReloadAgentConfiguration.setFrom(emptyList<Any>())
    if (nativeProbe) {
        dependsOn(prepareNativeFixtures)
        timeout.set(Duration.ofMinutes(2))
        systemProperty("projectTabIcons.probe.dir", nativeProbeDirectory.get().asFile.absolutePath)
        systemProperty("idea.trust.all.projects", "true")
        args(nativeProbeDirectory.get().dir("projects/one").asFile.absolutePath)
    }
}
if (nativeProbe) {
    tasks.register("nativeIntegrationTest") {
        dependsOn("runIde")
        val report = nativeProbeDirectory.map { it.file("results.json") }
        doLast {
            val file = report.get().asFile
            check(file.isFile && Regex("\"success\"\\s*:\\s*true").containsMatchIn(file.readText())) {
                "Native probe failed; see ${file.absolutePath} and the sandbox IDE log."
            }
        }
    }
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
