package com.xai.trident.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Recover;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Service
public class PdfProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(PdfProcessingService.class);
    private final AtomicLong barcodeCount = new AtomicLong(0);

    @Value("${pdfprocessing.barcode.dir:barcodes}")
    private String barcodeDir;

    @PostConstruct
    public void init() throws IOException {
        Path dirPath = Paths.get(barcodeDir);
        Files.createDirectories(dirPath);
        logger.info("Initialized barcode directory: {}", dirPath.toAbsolutePath());
        // try-with-resources required: Files.list returns a Stream that holds
        // an open directory handle until close() is called. Leaking these
        // accumulates file descriptors and eventually hits ulimit.
        try (Stream<Path> entries = Files.list(dirPath)) {
            barcodeCount.set(entries.filter(p -> p.toString().endsWith(".png")).count());
        }
    }

    @Async
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000), value = IOException.class)
    public CompletableFuture<String> extractTextFromPdfAsync(String filePath) throws IOException {
        // IOException now propagates instead of being wrapped — @Retryable's
        // proxy can finally see it and actually retry. Previously the
        // RuntimeException wrap below silently broke retries.
        String result = extractTextFromPdf(filePath);
        return CompletableFuture.completedFuture(result);
    }

    public String extractTextFromPdf(String filePath) throws IOException {
        logger.info("Extracting text from PDF: {}", filePath);
        if (!filePath.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("File must be a PDF: " + filePath);
        }
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            throw new IOException("PDF file not found or unreadable: " + filePath);
        }
        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text == null || text.trim().isEmpty()) {
                logger.warn("No text extracted from PDF: {}", filePath);
                return "";
            }
            logger.info("Text extracted successfully from PDF: {} (length: {})", filePath, text.length());
            return text;
        }
        // IOException intentionally not caught here — it propagates to the
        // @Retryable-annotated caller so the retry/recover machinery can run.
    }

    /**
     * Recovery for {@link #extractTextFromPdfAsync(String)} after all retries
     * are exhausted. The return type and parameter list must match the
     * @Retryable method (CompletableFuture&lt;String&gt; return, then
     * (Throwable, methodArgs...)) — Spring matches by type, and the previous
     * version's {@code String} return type silently never matched.
     */
    @Recover
    public CompletableFuture<String> recoverFromExtractFailure(IOException e, String filePath) {
        logger.error("Failed to extract text from PDF '{}' after retries: {}", filePath, e.getMessage());
        return CompletableFuture.completedFuture("");
    }

    @Async
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000), value = IOException.class)
    public CompletableFuture<String> generateBarcodeAsync(String data) throws IOException {
        String result = generateBarcode(data);
        barcodeCount.incrementAndGet();
        return CompletableFuture.completedFuture(result);
    }

    public String generateBarcode(String data) throws IOException {
        logger.info("Generating barcode for data: {}", data);
        if (data == null || data.trim().isEmpty()) {
            throw new IllegalArgumentException("Barcode data cannot be empty");
        }

        BitMatrix bitMatrix;
        try {
            // ZXing's WriterException is a "bad data" failure — retrying with the
            // same input won't help. Translate it to IllegalArgumentException so
            // the @Retryable proxy doesn't burn attempts on it.
            bitMatrix = new MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, 200, 200);
        } catch (WriterException e) {
            logger.error("Failed to encode barcode for data '{}': {}", data, e.getMessage());
            throw new IllegalArgumentException("Barcode encoding failed: " + e.getMessage(), e);
        }

        // IOException from filesystem writes is potentially transient (disk
        // contention, EBUSY, etc.) — let it propagate so @Retryable can retry.
        Path outputPath = Paths.get(barcodeDir, "barcode_" + UUID.randomUUID() + ".png");
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", outputPath);
        String barcodePath = outputPath.toAbsolutePath().toString();

        logger.info("Barcode generated successfully at: {}", barcodePath);
        return barcodePath;
    }

    // No @Transactional — pure filesystem delete, no JPA work. The previous
    // annotation reserved a DB connection from the pool for the entire call
    // without ever issuing a query (audit 2.18).
    public void cleanupBarcode(String barcodePath) {
        try {
            Path path = Paths.get(barcodePath);
            if (Files.deleteIfExists(path)) {
                barcodeCount.decrementAndGet();
                logger.info("Cleaned up barcode file: {}", barcodePath);
            }
        } catch (IOException e) {
            logger.warn("Failed to clean up barcode file '{}': {}", barcodePath, e.getMessage());
        }
    }

    /**
     * Deletes every {@code .png} barcode file under the configured barcode
     * directory and returns the number of files removed. Owned here (rather
     * than in {@code AdminController}) so the directory path stays
     * encapsulated — the previous admin-side implementation scanned
     * {@code Paths.get(".")} (the JVM working directory) instead of the
     * configured barcode dir, which meant it usually deleted nothing.
     */
    public long cleanupAllBarcodes() throws IOException {
        Path dirPath = Paths.get(barcodeDir);
        if (!Files.exists(dirPath)) {
            return 0L;
        }
        // try-with-resources around Files.list as required (see init()).
        try (Stream<Path> entries = Files.list(dirPath)) {
            return entries
                    .filter(p -> p.toString().endsWith(".png"))
                    .peek(p -> cleanupBarcode(p.toString()))
                    .count();
        }
    }

    // Metrics method for AdminController
    public long getBarcodeCount() {
        try (Stream<Path> entries = Files.list(Paths.get(barcodeDir))) {
            long actualCount = entries
                .filter(p -> p.toString().endsWith(".png"))
                .count();
            barcodeCount.set(actualCount); // Sync with filesystem
            return actualCount;
        } catch (IOException e) {
            logger.error("Failed to count barcodes: {}", e.getMessage());
            return barcodeCount.get();
        }
    }
}
