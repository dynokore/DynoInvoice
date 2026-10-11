// build.gradle.kts — platform-libs root (STK-60, FR-L.6; INV-T-2860 / T16).
//
// No buildable code sits at the repository root: every module below is its own
// Gradle project, one directory per module (README.md). This file only holds
// what every module shares, so a module's own build.gradle.kts stays a plain
// declaration of its layer, its api/impl split and its dependencies.

allprojects {
    group = "com.dynokore.platform" // layering standard §2.3
}

subprojects {
    // STK-72: dependency locking is active on every resolvable configuration, the
    // same as every platform-build module. Lock files live per module, refreshed
    // with `./gradlew :<module>:dependencies --write-locks` when a dependency
    // changes; never hand-edited.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        dependencyLocking {
            lockAllConfigurations()
        }
    }
}

// INV-T-2860 follow-up: publish every module to the LAN GitLab package registry.
// (The modules apply `maven-publish` and declare publications, but until now none
// declared a repository, so `./gradlew publish` had nothing to publish to.)
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "gstMaven"
                    val gstMavenUri =
                        uri(providers.gradleProperty("gstMavenUrl").getOrElse("https://repo.dynokore.internal/gst-maven"))
                    url = gstMavenUri
                    // The LAN GitLab package registry has no TLS (TLS is a Wave 5 item), so
                    // Gradle needs an explicit opt-in to publish to its http URL. Opted in for
                    // that one host only, never for any other repository.
                    isAllowInsecureProtocol = gstMavenUri.scheme == "http" && gstMavenUri.host == "registry.example.internal"
                    credentials {
                        username = providers.gradleProperty("gstMavenUser").orNull
                        password = providers.gradleProperty("gstMavenPassword").orNull
                    }
                }
            }
        }
    }
}

// The expected publish set, written as `<project path> <publication> <artifactId> <version>` lines to
// build/publications.txt for CI's post-publish check (P20/P28: a module with a
// version that is not published must fail the job, never be skipped). Fails itself
// if a module has a version.txt (or is platform-libs-bom, versioned by CI) but no maven-publish publication.
tasks.register("listPublications") {
    val out = layout.buildDirectory.file("publications.txt")
    outputs.file(out)
    doLast {
        val lines = mutableListOf<String>()
        subprojects.forEach { sub ->
            if (sub.file("version.txt").isFile || sub.name == "platform-libs-bom") {
                val publishing = sub.extensions.findByType(PublishingExtension::class.java)
                val publications = publishing?.publications?.withType(MavenPublication::class.java)?.toList().orEmpty()
                if (publications.isEmpty()) {
                    throw GradleException("${sub.path} has a version but no Maven publication (P20/P28)")
                }
                publications.forEach { lines += "${sub.path} ${it.name} ${it.artifactId} ${sub.version}" }
            }
        }
        if (lines.isEmpty()) throw GradleException("no module has a version.txt: nothing to publish (P20/P28)")
        out.get().asFile.apply {
            parentFile.mkdirs()
            writeText(lines.sorted().joinToString("\n") + "\n")
        }
    }
}
