package com.dynokore.gst.reconciliation.adapters.export

import com.dynokore.gst.reconciliation.application.workpaper.export.ExportFormat
import com.dynokore.gst.reconciliation.application.workpaper.export.RenderedExport
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperExport
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FormatWorkPaperRendererTest {
    private class Fake(
        private val name: String,
        private val calls: MutableList<Pair<String, WorkPaperExport>>,
    ) : WorkPaperRenderer {
        val answer = RenderedExport(name, name.toByteArray())

        override fun render(
            export: WorkPaperExport,
            format: ExportFormat,
        ): RenderedExport {
            calls += name to export
            return answer
        }
    }

    private val calls = mutableListOf<Pair<String, WorkPaperExport>>()
    private val spreadsheet = Fake("spreadsheet", calls)
    private val pdf = Fake("pdf", calls)
    private val export =
        WorkPaperExport(
            "R1",
            "E1",
            "P1",
            "2026-11-01T00:00:00Z",
            2,
            "F5",
            emptyList(),
            "0.00",
            emptyList(),
            null,
            emptyList(),
        )

    @Test
    fun `INV-AC-24541 PDF calls only the PDF renderer with the same export object`() {
        val result = FormatWorkPaperRenderer(spreadsheet, pdf).render(export, ExportFormat.PDF)
        assertSame(pdf.answer, result)
        assertEquals(1, calls.size)
        assertEquals("pdf", calls[0].first)
        assertSame(export, calls[0].second)
    }

    @Test
    fun `INV-AC-24541 SPREADSHEET calls only the spreadsheet renderer with the same export object`() {
        val result = FormatWorkPaperRenderer(spreadsheet, pdf).render(export, ExportFormat.SPREADSHEET)
        assertSame(spreadsheet.answer, result)
        assertEquals(1, calls.size)
        assertEquals("spreadsheet", calls[0].first)
        assertSame(export, calls[0].second)
    }

    @Test
    fun `INV-AC-24541 a format with no renderer throws IllegalArgumentException naming it and calls neither`() {
        val only = FormatWorkPaperRenderer(mapOf(ExportFormat.SPREADSHEET to spreadsheet))
        val e = assertThrows<IllegalArgumentException> { only.render(export, ExportFormat.PDF) }
        assertTrue(e.message!!.contains("PDF"), e.message)
        assertEquals(emptyList<Pair<String, WorkPaperExport>>(), calls)
        val none = FormatWorkPaperRenderer(emptyMap())
        assertThrows<IllegalArgumentException> { none.render(export, ExportFormat.SPREADSHEET) }
        assertEquals(0, calls.size)
    }
}
