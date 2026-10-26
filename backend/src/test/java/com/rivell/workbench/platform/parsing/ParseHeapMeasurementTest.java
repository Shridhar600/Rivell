package com.rivell.workbench.platform.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.rivell.workbench.contracts.ParseStatus;
import com.sun.management.ThreadMXBean;

/**
 * How much heap one parse of a large contract needs. A measurement, not a regression
 * test, so it only runs on request:
 * {@code ./mvnw -q -B test -Dtest=ParseHeapMeasurementTest -Drivell.measureParseHeap=true}.
 * It prints the figures; add {@code -DargLine=-Xmx512m} to see whether a heap of that size
 * survives the parse.
 */
@EnabledIfSystemProperty(named = "rivell.measureParseHeap", matches = "true")
class ParseHeapMeasurementTest {

    private static final long TARGET_FILE_BYTES = 10L * 1024 * 1024;
    private static final int WORD_COUNT = 40_000;

    @Test
    void measuresPeakHeapForOneParseOfATenMegabyteContract() throws Exception {
        var docx = syntheticContract(TARGET_FILE_BYTES);
        var uncompressedMb = ZipGuard.scan(docx, 200L * 1024 * 1024).mainDocumentXml().length
                / (1024 * 1024);
        var parser = new DocxStructuralParser(new DocxParserProperties(200L * 1024 * 1024));
        collect();
        var baseline = usedHeap();
        var peak = new AtomicLong(baseline);
        var sampling = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(usedHeap(), Math::max);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampling.setDaemon(true);
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        var allocatedBefore = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
        var started = System.nanoTime();

        sampling.start();
        var result = parser.parse(new ByteArrayInputStream(docx), UUID.randomUUID());
        sampling.interrupt();
        sampling.join();

        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        var allocated = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                - allocatedBefore;
        var retained = usedHeapAfterCollection() - baseline;
        assertThat(result.status()).isEqualTo(ParseStatus.OK);
        System.out.printf(
                "PARSE-HEAP file=%d MB (document.xml %d MB unpacked) nodes=%d elapsed=%d ms%n"
                        + "PARSE-HEAP baseline=%d MB (the file bytes included) "
                        + "peak-used=%d MB (garbage included, so an upper bound) "
                        + "peak-above-baseline=%d MB retained-by-result=%d MB "
                        + "allocated-in-total=%d MB%n",
                docx.length / (1024 * 1024), uncompressedMb, result.nodes().size(), elapsedMillis,
                baseline / (1024 * 1024), peak.get() / (1024 * 1024),
                (peak.get() - baseline) / (1024 * 1024), retained / (1024 * 1024),
                allocated / (1024 * 1024));
    }

    /** Paragraphs with ordinary-looking text, a heading now and then, and small tables. */
    static byte[] syntheticContract(long targetBytes) throws IOException {
        var random = new Random(81);
        var words = new String[WORD_COUNT];
        for (int i = 0; i < words.length; i++) {
            var word = new StringBuilder();
            for (int c = 3 + random.nextInt(9); c > 0; c--) {
                word.append((char) ('a' + random.nextInt(26)));
            }
            words[i] = word.toString();
        }
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>""");
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            write(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body>");
            for (int n = 1; output.size() < targetBytes; n++) {
                if (n % 40 == 0) {
                    write(zip, "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>" + (n / 40) + ". "
                            + sentence(random, words, 3) + "</w:t></w:r></w:p>");
                } else if (n % 25 == 0) {
                    write(zip, table(random, words));
                } else {
                    write(zip, "<w:p><w:r><w:t xml:space=\"preserve\">" + (n / 40 + 1) + "." + (n % 40)
                            + " " + sentence(random, words, 40 + random.nextInt(60))
                            + "</w:t></w:r></w:p>");
                }
            }
            write(zip, "</w:body></w:document>");
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static String table(Random random, String[] words) {
        var table = new StringBuilder("<w:tbl><w:tblPr/><w:tblGrid><w:gridCol/><w:gridCol/><w:gridCol/></w:tblGrid>");
        for (int row = 0; row < 4; row++) {
            table.append("<w:tr>");
            for (int cell = 0; cell < 3; cell++) {
                table.append("<w:tc><w:p><w:r><w:t>").append(sentence(random, words, 8))
                        .append("</w:t></w:r></w:p></w:tc>");
            }
            table.append("</w:tr>");
        }
        return table.append("</w:tbl>").toString();
    }

    private static String sentence(Random random, String[] words, int length) {
        var text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            text.append(i == 0 ? "" : " ").append(words[random.nextInt(words.length)]);
        }
        return text.append('.').toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        write(zip, content.strip());
        zip.closeEntry();
    }

    private static void write(ZipOutputStream zip, String content) throws IOException {
        zip.write(content.getBytes(StandardCharsets.UTF_8));
    }

    private static long usedHeap() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap.getUsed();
    }

    private static long usedHeapAfterCollection() {
        collect();
        return usedHeap();
    }

    private static void collect() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
