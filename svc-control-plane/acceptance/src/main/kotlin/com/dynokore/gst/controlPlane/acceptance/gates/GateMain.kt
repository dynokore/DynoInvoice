package com.dynokore.gst.controlPlane.acceptance.gates

import java.io.File
import kotlin.system.exitProcess

/** Entry point of the repository gates, run by the Gradle tasks operationsGate, acKeyCoverage, bundlePinGate. */
object GateMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val gate = args.firstOrNull() ?: usage()
        val options = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }

        fun dir(name: String) = File(options[name] ?: usage())

        val findings =
            when (gate) {
                "operations" -> {
                    operations(options)
                }

                "ac-keys" -> {
                    val root = dir("--root")
                    val modules = root.listFiles { f -> f.isDirectory && File(f, "src").isDirectory }.orEmpty().toList()
                    val table = File(root, AcKeyGate.NAME).takeIf { it.isFile }?.readText()
                    AcKeyGate.check(table, AcKeyGate.testNames(modules))
                }

                "bundle-pin" -> {
                    bundlePin(dir("--root"))
                }

                else -> {
                    usage()
                }
            }
        findings.forEach { System.err.println(it) }
        if (findings.isNotEmpty()) exitProcess(1)
        println("$gate gate: no finding")
    }

    private fun operations(options: Map<String, String>): List<Finding> {
        val tableDir = File(options["--table"] ?: usage())
        val contract = File(options["--contract"] ?: usage())
        val sources = File(options["--adapters"] ?: usage()).walkTopDown().filter { it.isFile }.toList()
        val table = File(tableDir, OperationsGate.NAME).takeIf { it.isFile }?.readText()
        val code = OperationsGate.Code(OperationsGate.overrides(sources), OperationsGate.stubs(sources))
        return OperationsGate.check(
            File(contract, "openapi.yaml").readText(),
            File(contract, "asyncapi.yaml").readText(),
            table,
            code,
        ) +
            DoneTasksGate.check(
                File(tableDir, DoneTasksGate.NAME).takeIf { it.isFile }?.readText(),
                table,
                code,
            ) +
            OperationOwnersGate.check(
                File(tableDir, OperationOwnersGate.NAME).takeIf { it.isFile }?.readText(),
                table,
            )
    }

    private fun bundlePin(root: File) =
        BundlePinGate.check(
            File(root, "gradle/libs.versions.toml").takeIf { it.isFile }?.readText(),
            File(root, BundlePinGate.NAME).takeIf { it.isFile }?.readText(),
        )

    private fun usage(): Nothing {
        System.err.println("usage: GateMain operations --contract=DIR --table=DIR --adapters=DIR")
        System.err.println("       GateMain ac-keys --root=DIR")
        System.err.println("       GateMain bundle-pin --root=DIR")
        exitProcess(2)
    }
}
