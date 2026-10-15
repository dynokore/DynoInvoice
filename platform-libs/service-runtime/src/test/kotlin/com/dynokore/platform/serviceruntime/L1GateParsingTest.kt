package com.dynokore.platform.serviceruntime

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The layer gate's source parsing: comments, strings and several imports on one line (INV-T-4747 review). */
class L1GateParsingTest {
    @Test
    fun `fails the second Java import on one line`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "crypto",
            "src/test/java/com/dynokore/platform/crypto/J.java",
            "package com.dynokore.platform.crypto;\n" +
                "import java.util.List; import com.dynokore.platform.tenancykernel.Stub;\nclass J {}\n",
        )

        fails(repo, "'crypto' layer does not depends on 'tenancy-kernel' layer")
    }

    @Test
    fun `an import inside a comment or a string is not an import`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "crypto",
            "src/test/java/com/dynokore/platform/crypto/J.java",
            "package com.dynokore.platform.crypto;\n// import com.dynokore.platform.tenancykernel.A;\n" +
                "/* import com.dynokore.platform.tenancykernel.B; */\n" +
                "class J { String s = \"import com.dynokore.platform.tenancykernel.C;\"; }\n",
        )

        assertClean(repo)
    }

    @Test
    fun `fails a package line inside a block comment that hides the real package`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "crypto",
            "src/main/java/com/dynokore/platform/crypto/J.java",
            "/*\npackage com.dynokore.platform.crypto;\n*/\npackage org.evil;\nclass J {}\n",
        )
        repo.file(
            "crypto",
            "src/main/kotlin/com/dynokore/platform/crypto/K.kt",
            "/* package com.dynokore.platform.crypto */\npackage org.evil\nclass K\n",
        )
        // Kotlin block comments nest: the compiler reads the last package line as the declaration
        repo.file(
            "crypto",
            "src/main/kotlin/com/dynokore/platform/crypto/N.kt",
            "/* /* */\npackage com.dynokore.platform.crypto\n*/\npackage org.evil\nclass N\n",
        )

        val findings = Rules.findings(repo.dir, Konsist.scopeFromExternalDirectory(repo.dir.absolutePath))
        val evil = "package org.evil is not under com.dynokore.platform.crypto"
        assertTrue(findings.count { evil in it } == 3) { "$findings" }
    }

    @Test
    fun `fails a file whose only package line is in a comment`(
        @TempDir dir: File,
    ) {
        val repo = writeL1ModuleStubs(dir)
        repo.file(
            "crypto",
            "src/main/java/com/dynokore/platform/crypto/J.java",
            "// package com.dynokore.platform.crypto;\nclass J {}\n",
        )

        fails(repo, "declares no package")
    }
}
