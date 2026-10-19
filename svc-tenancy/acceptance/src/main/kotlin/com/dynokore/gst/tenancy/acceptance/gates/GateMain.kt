package com.dynokore.gst.tenancy.acceptance.gates

import java.io.File
import kotlin.system.exitProcess

/** Entry point of the two repository gates; run by the Gradle tasks `operationsGate` and `acKeyCoverage`. */
object GateMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val gate = args.firstOrNull() ?: usage()
        val options = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }

        fun dir(name: String) = File(options[name] ?: usage())

        val findings =
            when (gate) {
                "operations" -> {
                    val contract = dir("--contract")
                    val sources = dir("--adapters").walkTopDown().filter { it.isFile }.toList()
                    val tests = testSources(dir("--table"))
                    OperationsGate.check(
                        File(contract, "openapi.yaml").readText(),
                        File(contract, "asyncapi.yaml").readText(),
                        File(dir("--table"), OperationsGate.NAME).takeIf { it.isFile }?.readText(),
                        OperationsGate.Code(OperationsGate.overrides(sources), OperationsGate.stubs(sources)),
                        OperationsGate.classNames(tests),
                    )
                }

                "ac-keys" -> {
                    val root = dir("--root")
                    val modules = root.listFiles { f -> f.isDirectory && File(f, "src").isDirectory }.orEmpty().toList()
                    val table = File(root, AcKeyGate.NAME).takeIf { it.isFile }?.readText()
                    AcKeyGate.check(table, AcKeyGate.testNames(modules))
                }

                else -> {
                    usage()
                }
            }
        findings.forEach { System.err.println(it) }
        if (findings.isNotEmpty()) exitProcess(1)
        println("$gate gate: no finding")
    }

    /** Every Kotlin file under a `src/test` or `src/integrationTest` folder of a module of the repository. */
    private fun testSources(root: File): List<File> =
        root
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.path.contains("/src/test/") || f.path.contains("/src/integrationTest/") }
            .toList()

    private fun usage(): Nothing {
        System.err.println("usage: GateMain operations --contract=DIR --table=DIR --adapters=DIR")
        System.err.println("       GateMain ac-keys --root=DIR")
        exitProcess(2)
    }
}
