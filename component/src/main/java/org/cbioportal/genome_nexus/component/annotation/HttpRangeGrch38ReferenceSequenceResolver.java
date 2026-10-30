package org.cbioportal.genome_nexus.component.annotation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * W3: GRCh38 reference resolver that fetches only the needed slices over
 * HTTP {@code Range} requests, using a {@code .fai} index to translate
 * 0-based half-open {@code [start, end)} coordinates into file byte ranges.
 * Defaults point at the same GCS reference the AlphaGenome SDK notebook
 * uses, so no multi-gigabyte FASTA download is required.
 *
 * <p>Out-of-range positions are {@code N}-padded, mirroring the SDK
 * {@code FastaExtractor} behavior.
 */
public class HttpRangeGrch38ReferenceSequenceResolver implements Grch38ReferenceSequenceResolver
{
    private final String fastaUrl;
    private final Map<String, FaiEntry> index = new LinkedHashMap<>();
    private final HttpClient httpClient;

    public HttpRangeGrch38ReferenceSequenceResolver(String fastaUrl, String faiUrl)
    {
        if (fastaUrl == null || fastaUrl.isEmpty()) {
            throw new IllegalArgumentException("fastaUrl must not be empty");
        }
        if (faiUrl == null || faiUrl.isEmpty()) {
            throw new IllegalArgumentException("faiUrl must not be empty");
        }
        this.fastaUrl = fastaUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        parseIndex(fetchIndexBody(faiUrl), faiUrl);
        if (index.isEmpty()) {
            throw new IllegalStateException("Empty FASTA index from " + faiUrl);
        }
    }

    @Override
    public String extract(String chrom, int zeroBasedStart, int zeroBasedEndExclusive)
    {
        FaiEntry entry = index.get(chrom);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown contig: " + chrom);
        }
        if (zeroBasedEndExclusive <= zeroBasedStart) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int start = zeroBasedStart;
        if (start < 0) {
            for (int i = start; i < 0; i++) {
                out.append('N');
            }
            start = 0;
        }
        int end = (int) Math.min((long) zeroBasedEndExclusive, entry.length);
        if (end > start) {
            out.append(fetchBases(entry, start, end));
        }
        for (int i = Math.max(start, (int) entry.length); i < zeroBasedEndExclusive; i++) {
            out.append('N');
        }
        return out.toString().toUpperCase();
    }

    private String fetchBases(FaiEntry entry, int start, int endExclusive)
    {
        long firstByte = entry.offset + (start / entry.lineLength) * entry.lineBytes
            + (start % entry.lineLength);
        long lastByte = entry.offset + ((endExclusive - 1) / entry.lineLength) * entry.lineBytes
            + ((endExclusive - 1) % entry.lineLength);
        byte[] body = fetchRange(firstByte, lastByte);
        StringBuilder bases = new StringBuilder();
        for (byte b : body) {
            if (b != '\n' && b != '\r') {
                bases.append((char) b);
            }
        }
        int expected = endExclusive - start;
        if (bases.length() != expected) {
            throw new IllegalStateException(
                "Expected " + expected + " bases for [" + start + "," + endExclusive
                    + ") but decoded " + bases.length());
        }
        return bases.toString();
    }

    /**
     * Override point for tests: fetch raw bytes {@code [firstByte, lastByte]}.
     */
    protected byte[] fetchRange(long firstByte, long lastByte)
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(fastaUrl))
            .header("Range", "bytes=" + firstByte + "-" + lastByte)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        try {
            HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 206 && response.statusCode() != 200) {
                throw new IllegalStateException(
                    "Range request failed with HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Range request failed", e);
        }
    }

    /**
     * Override point for tests: fetch the raw {@code .fai} text.
     */
    protected String fetchIndexBody(String faiUrl)
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(faiUrl))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                    "FASTA index request failed with HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FASTA index request failed", e);
        }
    }

    private void parseIndex(String body, String faiUrl)
    {
        for (String line : body.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length == 1) {
                parts = line.split("\\s+");
            }
            if (parts.length < 5) {
                throw new IllegalStateException("Malformed .fai line: " + line);
            }
            index.put(parts[0], new FaiEntry(
                Long.parseLong(parts[1]),
                Long.parseLong(parts[2]),
                Long.parseLong(parts[3]),
                Long.parseLong(parts[4])));
        }
    }

    private static final class FaiEntry
    {
        final long length;
        final long offset;
        final long lineLength;
        final long lineBytes;

        FaiEntry(long length, long offset, long lineLength, long lineBytes)
        {
            this.length = length;
            this.offset = offset;
            this.lineLength = lineLength;
            this.lineBytes = lineBytes;
        }
    }
}
