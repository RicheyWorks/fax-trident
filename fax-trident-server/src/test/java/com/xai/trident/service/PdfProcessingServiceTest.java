package com.xai.trident.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link PdfProcessingService}. Verifies the sync
 * methods directly — the {@code @Async}/{@code @Retryable} wrappers are
 * covered by integration tests because they only do something useful
 * inside a Spring context.
 *
 * <p>Each test owns a {@code @TempDir} that the service's
 * {@code barcodeDir} field is pointed at via reflection, so generated
 * barcode files don't leak between tests. {@link #setUp()} also calls
 * the service's {@code @PostConstruct init()} manually so the
 * {@code barcodeCount} field is in the same state it would be in under
 * Spring's lifecycle.
 *
 * <p>PDFs used for the extract-text tests are generated with PDFBox in
 * the {@code @TempDir} — no fixture files committed to the repo, so the
 * tests are hermetic and don't drift if PDFBox bumps its output format.
 */
public class PdfProcessingServiceTest {

    @TempDir
    Path tempDir;

    private PdfProcessingService service;
    private Path barcodeDir;

    @BeforeEach
    void setUp() throws IOException {
        barcodeDir = tempDir.resolve("barcodes");
        service = new PdfProcessingService();
        // The barcodeDir field is @Value-injected at runtime; in a unit test
        // we set it directly. ReflectionTestUtils is Spring's sanctioned
        // helper for this pattern — clearer at the call site than raw
        // java.lang.reflect.Field.setAccessible(true).
        ReflectionTestUtils.setField(service, "barcodeDir", barcodeDir.toString());
        service.init(); // creates the directory and counts existing PNGs (zero on a fresh @TempDir)
    }

    // ── extractTextFromPdf ────────────────────────────────────────────

    @Test
    void extractTextFromPdf_validPdfWithText_returnsExtractedText() throws IOException {
        Path pdf = writePdfWithText(tempDir.resolve("hello.pdf"), "Hello, fax-trident");

        String text = service.extractTextFromPdf(pdf.toString());

        assertNotNull(text);
        assertTrue(text.contains("Hello, fax-trident"),
                "extracted text should include the content written to the PDF");
    }

    @Test
    void extractTextFromPdf_emptyPdf_returnsEmptyString() throws IOException {
        Path pdf = writePdfWithoutText(tempDir.resolve("blank.pdf"));

        String text = service.extractTextFromPdf(pdf.toString());

        // The service explicitly trims-and-checks the result; a PDF with
        // only blank pages must surface as "" so callers can branch on it
        // (vs. the missing-file IOException case).
        assertEquals("", text);
    }

    @Test
    void extractTextFromPdf_nonPdfExtension_throwsIllegalArgumentException() {
        // Pre-flight extension check happens before the file is opened, so
        // it doesn't matter whether the file exists.
        assertThrows(IllegalArgumentException.class,
                () -> service.extractTextFromPdf(tempDir.resolve("not-a-pdf.txt").toString()));
    }

    @Test
    void extractTextFromPdf_missingFile_throwsIOException() {
        // Distinct from the "non-PDF extension" case: the path ends in .pdf
        // so the service tries to open it, which is where the IOException
        // surfaces. @Retryable retries on this; @Recover then surfaces the
        // empty string. The async wrapper isn't covered by this unit test,
        // so the raw IOException must propagate from the sync method.
        assertThrows(IOException.class,
                () -> service.extractTextFromPdf(tempDir.resolve("missing.pdf").toString()));
    }

    @Test
    void extractTextFromPdf_corruptPdf_throwsIOException() throws IOException {
        // Filename ends in .pdf but the bytes aren't a real PDF — PDFBox's
        // loader fails with IOException, which is what @Retryable matches.
        Path fake = tempDir.resolve("corrupt.pdf");
        Files.write(fake, "this is not a pdf, just some bytes\n".getBytes());

        assertThrows(IOException.class, () -> service.extractTextFromPdf(fake.toString()));
    }

    // ── generateBarcode ───────────────────────────────────────────────

    @Test
    void generateBarcode_validData_writesPngFileAndReturnsAbsolutePath() throws IOException {
        String returnedPath = service.generateBarcode("fax_test_123");

        assertNotNull(returnedPath);
        assertTrue(returnedPath.endsWith(".png"));
        Path produced = Path.of(returnedPath);
        assertTrue(Files.exists(produced), "file at returned path must exist on disk");
        assertTrue(produced.startsWith(barcodeDir.toAbsolutePath()),
                "barcode must be written inside the configured directory, not somewhere else");
        // PNG starts with the 8-byte magic 89 50 4E 47 0D 0A 1A 0A.
        byte[] header = new byte[8];
        try (var in = Files.newInputStream(produced)) {
            in.read(header);
        }
        assertEquals((byte) 0x89, header[0]);
        assertEquals((byte) 0x50, header[1]); // 'P'
        assertEquals((byte) 0x4E, header[2]); // 'N'
        assertEquals((byte) 0x47, header[3]); // 'G'
    }

    @Test
    void generateBarcode_emptyData_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.generateBarcode(""));
        assertThrows(IllegalArgumentException.class, () -> service.generateBarcode("   "));
    }

    @Test
    void generateBarcode_nullData_throwsIllegalArgumentException() {
        // The null-guard runs before the trim-check, so the contract is the
        // same exception type either way. Documenting both ensures a future
        // change to the validation order doesn't silently downgrade the
        // null case to NPE.
        assertThrows(IllegalArgumentException.class, () -> service.generateBarcode(null));
    }

    // ── cleanupBarcode (single file) ──────────────────────────────────

    @Test
    void cleanupBarcode_existingFile_deletesIt() throws IOException {
        String path = service.generateBarcode("fax_to_be_deleted");
        assertTrue(Files.exists(Path.of(path)));

        service.cleanupBarcode(path);

        assertFalse(Files.exists(Path.of(path)),
                "after cleanup the barcode file must no longer be on disk");
    }

    @Test
    void cleanupBarcode_missingFile_isSilentNoOp() {
        // The audit comment on cleanupBarcode notes it's a pure filesystem
        // delete: missing files don't throw, they just log and return. Pin
        // that contract so future error-handling changes are explicit.
        Path bogus = tempDir.resolve("never-existed.png");
        assertFalse(Files.exists(bogus));

        // No assertion needed beyond "doesn't throw" — that IS the contract.
        service.cleanupBarcode(bogus.toString());
    }

    // ── cleanupAllBarcodes (sweep) ────────────────────────────────────

    @Test
    void cleanupAllBarcodes_deletesAllPngsAndReturnsCount() throws IOException {
        service.generateBarcode("a");
        service.generateBarcode("b");
        service.generateBarcode("c");

        long removed = service.cleanupAllBarcodes();

        assertEquals(3L, removed, "all three generated barcodes should be deleted");
        try (Stream<Path> entries = Files.list(barcodeDir)) {
            assertEquals(0L, entries.filter(p -> p.toString().endsWith(".png")).count());
        }
    }

    @Test
    void cleanupAllBarcodes_directoryMissing_returnsZero() throws IOException {
        // Repoint at a directory that doesn't exist. The audit comment is
        // explicit that the method short-circuits on missing dir rather
        // than throwing — that's the surface the AdminController endpoint
        // delegates to and assumes is safe.
        ReflectionTestUtils.setField(service, "barcodeDir",
                tempDir.resolve("does-not-exist").toString());

        assertEquals(0L, service.cleanupAllBarcodes());
    }

    // ── getBarcodeCount ───────────────────────────────────────────────

    @Test
    void getBarcodeCount_returnsActualFilesystemCount() throws IOException {
        assertEquals(0L, service.getBarcodeCount(), "fresh directory starts empty");
        service.generateBarcode("one");
        service.generateBarcode("two");
        assertEquals(2L, service.getBarcodeCount());
    }

    // ── helpers ───────────────────────────────────────────────────────

    private Path writePdfWithText(Path out, String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            doc.save(out.toFile());
        }
        return out;
    }

    private Path writePdfWithoutText(Path out) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(out.toFile());
        }
        return out;
    }
}
