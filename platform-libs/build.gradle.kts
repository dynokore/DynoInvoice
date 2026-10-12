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

// INV-T-4356: every module that publishes an `api` and an `impl` publication publishes the impl's dependency metadata
// (STK-60, STK-01 rule (a), TPL-01: a consumer declares the BOM and the impl artefacts alone). The `impl` publication is
// built with `artifact(implJar)` and no software component, so Gradle writes a POM with no dependency at all; every
// impl POM published up to 2026-10-13 declared none. The POM's dependencies are derived here from mechanical facts of
// the `impl` source set's resolved runtime classpath, never from a hand-kept list:
//   - the first-level external modules, at their resolved (locked) versions. A platform (`platform(...)`, e.g. the
//     Spring Boot or Resilience4j BOM) holds no classes and only fixes versions, which the POM already states for each
//     module, so it is not written: as a `dependencyManagement` import it would make every consumer download the
//     platform's whole import chain to resolve nothing more (a transitive POM's imports apply to that POM alone);
//   - each project dependency as that project's published coordinates (its `api` publication, the `-api` artefact);
//     a project with no publication whose classes `implJar` copies in (product-runtime's generated contract clients,
//     INV-Q-3852) is part of this artefact, so its own first-level dependencies are written instead;
//   - each file dependency as the artefact that file is: this module's own `main` output is its `-api` (at the same
//     version), another project's `implJar` or `impl` classes are that project's impl artefact;
//   - scope `compile` when a type of the dependency (or of its transitive graph) appears in a public or protected
//     signature of a class in the published impl jar (read from its class files), `runtime` otherwise.
// Test and build-only configurations are not on that classpath, and `implCompileOnly` dependencies are not published.
// Anything that cannot be classified (a file dependency that is no module output, an unresolved dependency, a project
// without a publication that is not copied into the impl jar, a signature type no dependency provides) fails the build.
// `verifyImplPoms` (run by `check`) generates each impl POM and fails unless it holds exactly that set, the module's
// own `-api` at the same version included, and every external module of the impl runtime classpath is listed or
// reached through a listed dependency. `-PimplPomFault=<module>` leaves that module's impl POM as the bare
// `artifact(implJar)` one: the negative test the CI job `impl-poms` runs, which must fail with "verifyImplPoms:".
// Never set it anywhere else.
val implPomFault: String? = providers.gradleProperty("implPomFault").orNull

/** One `<dependency>` of an impl POM. */
data class ImplPomDependency(val group: String, val artifact: String, val version: String, val scope: String) {
    val key get() = "$group:$artifact"

    override fun toString() = "$group:$artifact:$version ($scope)"
}

/** The internal names of the classes a JVM signature or descriptor refers to (JVMS 4.7.9.1, 4.3). */
class JvmSignatureTypes(private val s: String, private val out: MutableSet<String>) {
    private var i = 0

    fun parse() {
        if (s.startsWith("<")) formalTypeParameters()
        while (i < s.length) {
            when (s[i]) {
                '(', ')', '^' -> i++
                else -> type()
            }
        }
    }

    private fun formalTypeParameters() {
        i++
        while (s[i] != '>') {
            while (s[i] != ':') i++
            while (i < s.length && s[i] == ':') {
                i++
                if (s[i] == 'L' || s[i] == 'T' || s[i] == '[') referenceType()
            }
        }
        i++
    }

    private fun type() {
        if (s[i] in "BCDFIJSZV") i++ else referenceType()
    }

    private fun referenceType() {
        when (s[i]) {
            'L' -> classType()
            'T' -> i = s.indexOf(';', i) + 1
            '[' -> {
                i++
                type()
            }
            else -> throw GradleException("unexpected '${s[i]}' at $i in JVM signature $s")
        }
    }

    private fun classType() {
        i++
        var name = simpleName()
        out += name
        while (true) {
            when (s[i]) {
                '<' -> typeArguments()
                '.' -> {
                    i++
                    name = "$name$" + simpleName()
                    out += name
                }
                ';' -> {
                    i++
                    return
                }
                else -> throw GradleException("unexpected '${s[i]}' at $i in JVM signature $s")
            }
        }
    }

    private fun simpleName(): String {
        val start = i
        while (s[i] != ';' && s[i] != '<' && s[i] != '.') i++
        return s.substring(start, i)
    }

    private fun typeArguments() {
        i++
        while (s[i] != '>') {
            when (s[i]) {
                '*' -> i++
                '+', '-' -> {
                    i++
                    referenceType()
                }
                else -> referenceType()
            }
        }
        i++
    }
}

/**
 * The classes a public or protected signature of this class file refers to (superclass, interfaces, generic class
 * signature, public and protected fields and methods with their generic signatures and declared exceptions), or none
 * when the class itself is not public. Synthetic members and bridge methods are skipped.
 */
fun publicSignatureTypes(bytes: ByteArray): Set<String> {
    val input = java.io.DataInputStream(bytes.inputStream())
    check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
    input.readUnsignedShort()
    input.readUnsignedShort()
    val poolSize = input.readUnsignedShort()
    val utf8 = arrayOfNulls<String>(poolSize)
    val classNameIndex = IntArray(poolSize)
    var index = 1
    while (index < poolSize) {
        when (val tag = input.readUnsignedByte()) {
            1 -> utf8[index] = input.readUTF()
            7 -> classNameIndex[index] = input.readUnsignedShort()
            8, 16, 19, 20 -> input.readUnsignedShort()
            15 -> {
                input.readUnsignedByte()
                input.readUnsignedShort()
            }
            3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt()
            5, 6 -> {
                input.readLong()
                index++
            }
            else -> throw GradleException("unknown constant pool tag $tag")
        }
        index++
    }
    val types = sortedSetOf<String>()
    fun className(i: Int) = if (i == 0) null else utf8[classNameIndex[i]]
    fun signature(text: String) = JvmSignatureTypes(text, types).parse()

    fun classReference(name: String) {
        if (name.startsWith("[")) signature(name) else types += name
    }
    val classAccess = input.readUnsignedShort()
    input.readUnsignedShort()
    val superClass = className(input.readUnsignedShort())
    val interfaces = (1..input.readUnsignedShort()).map { className(input.readUnsignedShort()) }
    val visible = classAccess and 0x0001 != 0 && classAccess and 0x1000 == 0
    if (visible) {
        superClass?.let { classReference(it) }
        interfaces.filterNotNull().forEach { classReference(it) }
    }

    fun attributes(collect: Boolean) {
        repeat(input.readUnsignedShort()) {
            val name = utf8[input.readUnsignedShort()]
            val length = input.readInt()
            when {
                collect && name == "Signature" -> {
                    signature(utf8[input.readUnsignedShort()]!!)
                }
                collect && name == "Exceptions" -> {
                    repeat(input.readUnsignedShort()) { className(input.readUnsignedShort())?.let { classReference(it) } }
                }
                else -> input.skipBytes(length)
            }
        }
    }

    fun members(isMethod: Boolean) {
        repeat(input.readUnsignedShort()) {
            val access = input.readUnsignedShort()
            input.readUnsignedShort()
            val descriptor = utf8[input.readUnsignedShort()]!!
            val exposed = visible && access and 0x0005 != 0 && access and 0x1000 == 0 && !(isMethod && access and 0x0040 != 0)
            if (exposed) signature(descriptor)
            attributes(exposed)
        }
    }
    members(isMethod = false)
    members(isMethod = true)
    attributes(visible)
    return types
}

/** The class names (internal form) in a jar or a class output directory. */
fun classNamesIn(file: File): Set<String> =
    when {
        file.isDirectory ->
            file.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(file).invariantSeparatorsPath.removeSuffix(".class") }.toSet()
        file.isFile && file.name.endsWith(".jar") ->
            java.util.zip.ZipFile(file).use { zip ->
                zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                    .map { it.removeSuffix(".class") }.toSet()
            }
        else -> emptySet()
    }

/** True when the running JDK provides the class (java.*, javax.* of the JDK, jdk.*, ...). */
fun isJdkClass(internalName: String) = ClassLoader.getPlatformClassLoader().getResource("$internalName.class") != null

/**
 * The dependency set of [module]'s impl POM (see the block comment above). [implJarFile] is the jar the `impl`
 * publication uploads: its classes are the ones whose public signatures fix the scopes.
 */
fun implPomDependencies(module: Project, implJarFile: File): List<ImplPomDependency> {
    val sourceSets = module.the<SourceSetContainer>()
    val runtime = module.configurations.getByName(sourceSets["impl"].runtimeClasspathConfigurationName)
    val compile = module.configurations.getByName(sourceSets["impl"].compileClasspathConfigurationName)
    val problems = mutableListOf<String>()

    fun publication(target: Project, name: String): MavenPublication? {
        val publications =
            target.extensions.findByType(PublishingExtension::class.java)?.publications?.withType(MavenPublication::class.java)
                ?: return null
        return publications.findByName(name) ?: publications.singleOrNull()?.takeIf { name == "api" }
    }

    // A candidate dependency, the class names its own artefact holds and those its transitive graph adds.
    class Candidate(val group: String, val artifact: String, val version: String, val platform: Boolean) {
        fun key() = "$group:$artifact"

        val classes = mutableSetOf<String>()
        val transitiveClasses = mutableSetOf<String>()
    }
    val candidates = linkedMapOf<String, Candidate>()

    fun candidate(group: String, artifact: String, version: String, platform: Boolean = false): Candidate =
        candidates.getOrPut("$group:$artifact") { Candidate(group, artifact, version, platform) }.also {
            if (it.version != version) problems += "$group:$artifact is both ${it.version} and $version"
        }

    // Files per resolved component, for the class index.
    val filesByComponent = mutableMapOf<ComponentIdentifier, MutableSet<File>>()
    runtime.incoming.artifacts.artifacts.forEach {
        filesByComponent.getOrPut(it.id.componentIdentifier) { mutableSetOf() } += it.file
    }

    fun Candidate.index(root: ResolvedComponentResult) {
        val seen = mutableSetOf<ComponentIdentifier>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!seen.add(node.id)) continue
            val names = filesByComponent[node.id].orEmpty().flatMap { classNamesIn(it) }
            if (node == root) classes += names else transitiveClasses += names
            node.dependencies.filterIsInstance<ResolvedDependencyResult>().filter { !it.isConstraint }.forEach { queue += it.selected }
        }
    }

    // The classes `implJar` copies in from other projects are part of this artefact.
    val implJarInputs = module.tasks.named("implJar").get().inputs.files.files.map { it.canonicalFile }

    fun embedded(target: Project): Boolean {
        val dirs = target.the<SourceSetContainer>()["main"].output.classesDirs.files.map { it.canonicalFile }
        return implJarInputs.any { input -> dirs.any { input.startsWith(it) } }
    }

    fun addEdges(edges: Collection<DependencyResult>, via: String) {
        edges.filter { !it.isConstraint }.forEach { edge ->
            if (edge !is ResolvedDependencyResult) {
                problems += "unresolved dependency ${edge.requested.displayName}$via"
                return@forEach
            }
            val node = edge.selected
            when (val id = node.id) {
                is ModuleComponentIdentifier -> {
                    val category = edge.resolvedVariant.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name
                    val platform = category == Category.REGULAR_PLATFORM || category == Category.ENFORCED_PLATFORM
                    if (!platform) candidate(id.group, id.module, id.version, false).index(node)
                }
                is ProjectComponentIdentifier -> {
                    val target = module.rootProject.project(id.projectPath)
                    val api = publication(target, "api")
                    when {
                        api != null -> candidate(api.groupId, api.artifactId, api.version).index(node)
                        embedded(target) -> addEdges(node.dependencies, " (through ${target.path}, copied into the impl jar)")
                        else ->
                            problems += "project dependency ${target.path}$via has no publication and is not copied into the impl jar"
                    }
                }
                else -> problems += "dependency ${id.displayName}$via is neither a module nor a project"
            }
        }
    }
    addEdges(runtime.incoming.resolutionResult.root.dependencies, "")

    // File dependencies: this module's own `main` output, or another module's impl classes or `implJar`.
    val ownMain = sourceSets["main"].output.files.map { it.canonicalFile }.toSet()
    runtime.allDependencies.withType(FileCollectionDependency::class.java).forEach { dep ->
        val files = dep.files.files.map { it.canonicalFile }.toSet()
        val owner: Pair<Project, String>? =
            when {
                files.isNotEmpty() && ownMain.containsAll(files) -> module to "api"
                else ->
                    module.rootProject.subprojects.firstNotNullOfOrNull { other ->
                        val otherImplJar = other.tasks.findByName("implJar") as? Jar
                        val otherImpl = other.extensions.findByType(SourceSetContainer::class.java)?.findByName("impl")
                        val outputs =
                            listOfNotNull(otherImplJar?.archiveFile?.get()?.asFile) + otherImpl?.output?.files.orEmpty()
                        if (other != module && files.isNotEmpty() && outputs.map { it.canonicalFile }.containsAll(files)) {
                            other to "impl"
                        } else {
                            null
                        }
                    }
            }
        if (owner == null) {
            problems += "file dependency ${files.joinToString()} is no module's published output"
            return@forEach
        }
        val pub = publication(owner.first, owner.second)
        if (pub == null) {
            problems += "${owner.first.path} has no `${owner.second}` publication for file dependency ${files.joinToString()}"
            return@forEach
        }
        val classes =
            if (owner.second == "api") {
                files.flatMap { classNamesIn(it) }
            } else {
                files.flatMap { classNamesIn(it) } +
                    owner.first.the<SourceSetContainer>()["impl"].output.classesDirs.files.flatMap { classNamesIn(it) }
            }
        candidate(pub.groupId, pub.artifactId, pub.version).classes += classes
    }

    // Scopes: a dependency whose classes appear in a public signature of the published impl jar is `compile`.
    val ownClasses = classNamesIn(implJarFile)
    val signatureTypes = sortedSetOf<String>()
    java.util.zip.ZipFile(implJarFile).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }.forEach {
            signatureTypes += publicSignatureTypes(zip.getInputStream(it).readBytes())
        }
    }
    // Compile-only dependencies (`implCompileOnly`) are known but deliberately not published.
    val compileOnlyClasses = compile.incoming.artifactView { lenient(false) }.files.files.flatMap { classNamesIn(it) }.toSet()
    val compileScoped = mutableSetOf<String>()
    signatureTypes.filter { it !in ownClasses && !isJdkClass(it) }.forEach { type ->
        val providers =
            candidates.values.filter { !it.platform && type in it.classes }
                .ifEmpty { candidates.values.filter { !it.platform && type in it.transitiveClasses } }
        when {
            providers.isNotEmpty() -> providers.forEach { compileScoped += "${it.group}:${it.artifact}" }
            type in compileOnlyClasses -> Unit
            else -> problems += "signature type ${type.replace('/', '.')} is provided by no dependency of the impl"
        }
    }
    if (problems.isNotEmpty()) {
        throw GradleException("implPom: ${module.path} (INV-T-4356):\n" + problems.joinToString("\n") { "  - $it" })
    }
    return candidates.values.map {
        ImplPomDependency(it.group, it.artifact, it.version, if (it.key() in compileScoped) "compile" else "runtime")
    }.sortedBy { it.key }
}

subprojects {
    plugins.withId("maven-publish") {
        afterEvaluate {
            val publications = extensions.getByType<PublishingExtension>().publications.withType<MavenPublication>()
            publications.findByName("api") ?: return@afterEvaluate
            val implPublication = publications.findByName("impl") ?: return@afterEvaluate
            val module = this
            val implJarTask = tasks.named<Jar>("implJar")
            val dependenciesOfImpl by lazy { implPomDependencies(module, implJarTask.get().archiveFile.get().asFile) }

            if (implPomFault != name) {
                implPublication.pom.withXml {
                    val list = asNode().appendNode("dependencies")
                    dependenciesOfImpl.forEach {
                        list.appendNode("dependency").apply {
                            appendNode("groupId", it.group)
                            appendNode("artifactId", it.artifact)
                            appendNode("version", it.version)
                            appendNode("scope", it.scope)
                        }
                    }
                }
            }
            val generatePom = tasks.named<GenerateMavenPom>("generatePomFileForImplPublication")
            val implSourceSet = the<SourceSetContainer>()["impl"]
            val implClasspaths =
                listOf(implSourceSet.runtimeClasspathConfigurationName, implSourceSet.compileClasspathConfigurationName)
                    .map { configurations.getByName(it) }
            // The class index reads every jar and class directory of the impl classpaths, so they are built first.
            generatePom.configure { dependsOn(implJarTask, implClasspaths) }

            val modulePath = path
            val ownApi = publications.getByName("api")
            val verify =
                tasks.register("verifyImplPoms") {
                    group = "verification"
                    description = "Fails unless the impl POM declares the impl's own -api and runtime classpath (INV-T-4356)."
                    dependsOn(generatePom, implJarTask, implClasspaths)
                    doLast {
                        val pomFile = generatePom.get().destination
                        val document =
                            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                                .newDocumentBuilder().parse(pomFile)
                        val listed =
                            (0 until document.getElementsByTagNameNS("*", "dependency").length).map { n ->
                                val e = document.getElementsByTagNameNS("*", "dependency").item(n) as org.w3c.dom.Element
                                fun text(tag: String) = e.getElementsByTagNameNS("*", tag).item(0)?.textContent?.trim().orEmpty()
                                ImplPomDependency(text("groupId"), text("artifactId"), text("version"), text("scope").ifEmpty { "compile" })
                            }
                        val expected = dependenciesOfImpl
                        val problems = mutableListOf<String>()
                        val api = listed.singleOrNull { it.group == ownApi.groupId && it.artifact == ownApi.artifactId }
                        if (api == null || api.version != ownApi.version) {
                            problems += "lacks its own ${ownApi.groupId}:${ownApi.artifactId}:${ownApi.version} (found ${api ?: "none"})"
                        }
                        (expected - listed.toSet()).forEach { problems += "lacks $it" }
                        (listed - expected.toSet()).forEach { problems += "declares $it, which is not on the impl runtime classpath" }
                        // Every external module of the impl runtime classpath is listed or reached through a listed one.
                        val runtime = module.configurations.getByName(implSourceSet.runtimeClasspathConfigurationName)
                        val listedKeys = listed.map { it.key }.toSet()
                        val reached = mutableSetOf<ComponentIdentifier>()
                        val queue = ArrayDeque<ResolvedComponentResult>()
                        val rootNode = runtime.incoming.resolutionResult.root
                        fun enqueueFirstLevel(node: ResolvedComponentResult) {
                            node.dependencies.filterIsInstance<ResolvedDependencyResult>().filter { !it.isConstraint }.forEach { edge ->
                                val sel = edge.selected
                                val id = sel.id
                                val key =
                                    when (id) {
                                        is ModuleComponentIdentifier -> "${id.group}:${id.module}"
                                        is ProjectComponentIdentifier -> {
                                            val target = module.rootProject.project(id.projectPath)
                                            val pubs = target.extensions.findByType(PublishingExtension::class.java)
                                                ?.publications?.withType(MavenPublication::class.java)
                                            val p = pubs?.findByName("api") ?: pubs?.singleOrNull()
                                            if (p == null) {
                                                enqueueFirstLevel(sel) // copied into the impl jar: its own edges count
                                                null
                                            } else {
                                                "${p.groupId}:${p.artifactId}"
                                            }
                                        }
                                        else -> null
                                    }
                                if (key != null && key in listedKeys) queue += sel
                            }
                        }
                        enqueueFirstLevel(rootNode)
                        while (queue.isNotEmpty()) {
                            val node = queue.removeFirst()
                            if (!reached.add(node.id)) continue
                            node.dependencies.filterIsInstance<ResolvedDependencyResult>().filter { !it.isConstraint }
                                .forEach { queue += it.selected }
                        }
                        // A platform only fixes versions, which the POM states for each module (see above).
                        fun isPlatform(c: ResolvedComponentResult) =
                            c.variants.isNotEmpty() && c.variants.all {
                                it.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name in
                                    setOf(Category.REGULAR_PLATFORM, Category.ENFORCED_PLATFORM)
                            }
                        runtime.incoming.resolutionResult.allComponents
                            .filter { it.id is ModuleComponentIdentifier && it.id !in reached && !isPlatform(it) }
                            .forEach { problems += "external module ${it.id.displayName} of the impl runtime classpath is not reached" }
                        if (problems.isNotEmpty()) {
                            throw GradleException(
                                "verifyImplPoms: $modulePath (INV-T-4356), ${pomFile.name}:\n" + problems.joinToString("\n") { "  - $it" },
                            )
                        }
                        logger.lifecycle(
                            "verifyImplPoms: $modulePath ${implPublication.artifactId}:${implPublication.version} declares " +
                                listed.joinToString(", "),
                        )
                    }
                }
            tasks.named("check") { dependsOn(verify) }
        }
    }
}
