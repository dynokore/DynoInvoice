// build.gradle.kts — platform-libs root (STK-60, FR-L.6; INV-T-2860 / T16).
//
// No buildable code sits at the repository root: every module below is its own
// Gradle project, one directory per module (README.md). This file only holds
// what every module shares, so a module's own build.gradle.kts stays a plain
// declaration of its layer, its api/impl split and its dependencies.

// STK-72 (INV-T-4297, coordinator ruling INV-Q-4367, the same as INV-Q-4322 and INV-Q-4327):
// every resolvable configuration of every project (the java-platform platform-libs-bom
// included) is locked, the plugin classpath of the root and of each module too
// (buildscript-gradle.lockfile), and the settings classpath (settings-gradle.lockfile,
// settings.gradle.kts). All of it runs in strict lock mode, so a configuration without lock
// state, a resolution outside the lock files or a deleted lock file fails the build. Every
// downloaded artefact is verified against gradle/verification-metadata.xml in strict mode.
// Refresh after a dependency change: README.md, "Dependency locking and verification".
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
}

allprojects {
    group = "com.dynokore.platform" // layering standard §2.3

    buildscript.configurations.named("classpath") {
        resolutionStrategy.activateDependencyLocking()
    }
    if (this != rootProject) {
        // The root's own buildscript lock mode is set (and already final) in its buildscript block above.
        buildscript.dependencyLocking.lockMode.set(LockMode.STRICT)
    }
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

// Resolves every resolvable configuration of every project, so one
// `./gradlew resolveAndLockAll --write-locks` writes every gradle.lockfile; the build script and
// settings classpaths are resolved while the build is configured and are written in the same run.
allprojects {
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doLast {
            project.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
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

// INV-T-3937 (coordinator ruling INV-Q-3929): every module that publishes an `api` and an `impl` publication is
// checked by `verifySplitJars`, which `check` runs. service-runtime's `implJar` once wrote the same build/libs file as
// its api `jar`, so every published service-runtime-api from 1.2.0 to 3.3.0 held only the impl classes (rulepack-kernel
// 1.2.0/1.3.0 and adapter-runtime 2.0.0 likewise). The check reads the two jars the publications upload:
//   - the api jar holds exactly the class files of the `main` source set, and at least one;
//   - the impl jar holds exactly the class files of the `impl` source set, plus those the `implJar` task itself
//     copies in from other projects (product-runtime's generated contract clients, INV-Q-3852);
//   - no class file is in both.
// `-PsplitJarsFault=<module>` points that module's `implJar` back at build/libs: the negative test the CI job
// `split-jars` runs, which must fail with "verifySplitJars:". Never set it anywhere else.
val splitJarsFault: String? = providers.gradleProperty("splitJarsFault").orNull

subprojects {
    plugins.withId("maven-publish") {
        afterEvaluate {
            val publications = extensions.getByType<PublishingExtension>().publications.withType<MavenPublication>()
            val apiPublication = publications.findByName("api") ?: return@afterEvaluate
            val implPublication = publications.findByName("impl") ?: return@afterEvaluate
            val sourceSets = the<SourceSetContainer>()
            val implSourceSet =
                sourceSets.findByName("impl")
                    ?: throw GradleException("$path publishes an api and an impl publication but has no `impl` source set")
            val implJarTask = tasks.withType<Jar>().findByName("implJar")
            if (splitJarsFault == name) {
                implJarTask?.destinationDirectory?.set(layout.buildDirectory.dir("libs"))
            }

            fun jarOf(publication: MavenPublication) =
                publication.artifacts.singleOrNull { it.extension == "jar" && it.classifier.isNullOrEmpty() }
                    ?: throw GradleException("$path's `${publication.name}` publication has no single unclassified jar")
            val apiArtifact = jarOf(apiPublication)
            val implArtifact = jarOf(implPublication)
            val mainClasses = sourceSets["main"].output.classesDirs.asFileTree.matching { include("**/*.class") }
            val implSources: FileTree = implJarTask?.source?.asFileTree ?: implSourceSet.output.classesDirs.asFileTree
            val implClasses = implSources.matching { include("**/*.class") }
            val modulePath = path

            val verify =
                tasks.register("verifySplitJars") {
                    group = "verification"
                    description = "Fails unless the api jar holds exactly the main classes and the impl jar the impl classes."
                    dependsOn(apiArtifact, implArtifact)
                    inputs.files(mainClasses, implClasses)
                    doLast {
                        fun FileTree.classPaths(): Set<String> {
                            val paths = sortedSetOf<String>()
                            visit { if (!isDirectory) paths += relativePath.pathString }
                            return paths
                        }

                        fun classesIn(jar: File): Set<String> =
                            java.util.zip.ZipFile(jar).use { zip ->
                                zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toSortedSet()
                            }

                        fun sample(paths: Collection<String>) = paths.take(5).joinToString(", ") + if (paths.size > 5) ", ..." else ""

                        val expectedApi = mainClasses.classPaths()
                        val expectedImpl = implClasses.classPaths() +
                                implSourceSet.output.classesDirs.asFileTree.matching { include("**/*.class") }.classPaths()
                        val apiJar = apiArtifact.file
                        val implJar = implArtifact.file
                        val inApi = classesIn(apiJar)
                        val inImpl = classesIn(implJar)
                        val problems = mutableListOf<String>()
                        if (apiJar.canonicalFile == implJar.canonicalFile) {
                            problems += "the api and impl publications upload the same file $apiJar"
                        }
                        if (inApi.isEmpty()) problems += "the api jar $apiJar holds no class"
                        (inApi - expectedApi).takeIf { it.isNotEmpty() }?.let {
                            problems += "the api jar holds ${it.size} class(es) not of the main source set: ${sample(it)}"
                        }
                        (expectedApi - inApi).takeIf { it.isNotEmpty() }?.let {
                            problems += "the api jar lacks ${it.size} class(es) of the main source set: ${sample(it)}"
                        }
                        (inImpl - expectedImpl).takeIf { it.isNotEmpty() }?.let {
                            problems += "the impl jar holds ${it.size} class(es) not of the impl source set: ${sample(it)}"
                        }
                        (expectedImpl - inImpl).takeIf { it.isNotEmpty() }?.let {
                            problems += "the impl jar lacks ${it.size} class(es) of the impl source set: ${sample(it)}"
                        }
                        (inApi intersect inImpl).takeIf { it.isNotEmpty() }?.let {
                            problems += "${it.size} class(es) are in both jars: ${sample(it)}"
                        }
                        if (problems.isNotEmpty()) {
                            throw GradleException(
                                "verifySplitJars: $modulePath (INV-T-3937):\n" + problems.joinToString("\n") { "  - $it" },
                            )
                        }
                        logger.lifecycle(
                            "verifySplitJars: $modulePath api ${apiJar.name} ${inApi.size} classes, impl ${implJar.name} ${inImpl.size} classes",
                        )
                    }
                }
            tasks.named("check") { dependsOn(verify) }
        }
    }
}
