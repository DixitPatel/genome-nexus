package org.cbioportal.genome_nexus.component.annotation;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class HttpRangeGrch38ReferenceSequenceResolverTest
{
    // chrT: "ACGTACGT" wrapped at 4 bases per line, contig data at offset 10.
    private static final String FAI = "chrT\t8\t10\t4\t5\n";
    private static final byte[] FASTA = "0123456789ACGT\nACGT\n".getBytes(StandardCharsets.UTF_8);

    private static class ScriptedResolver extends HttpRangeGrch38ReferenceSequenceResolver
    {
        ScriptedResolver()
        {
            super("http://localhost/genome.fa", "http://localhost/genome.fa.fai");
        }

        @Override
        protected String fetchIndexBody(String faiUrl)
        {
            return FAI;
        }

        @Override
        protected byte[] fetchRange(long firstByte, long lastByte)
        {
            return Arrays.copyOfRange(FASTA, (int) firstByte, (int) lastByte + 1);
        }
    }

    @Test
    public void extractsSingleLineSlice()
    {
        // 0-based [0,4) -> file bytes [10,13] -> "ACGT".
        assertEquals("ACGT", new ScriptedResolver().extract("chrT", 0, 4));
    }

    @Test
    public void extractsMultiLineSlice()
    {
        // 0-based [2,6) spans the newline: bytes [12,16] -> "GTAC".
        assertEquals("GTAC", new ScriptedResolver().extract("chrT", 2, 6));
    }

    @Test
    public void padsOutOfRangeWithN()
    {
        assertEquals("N", new ScriptedResolver().extract("chrT", -1, 0));
        assertEquals("TNN", new ScriptedResolver().extract("chrT", 7, 10));
    }

    @Test
    public void rejectsUnknownContig()
    {
        try {
            new ScriptedResolver().extract("chrM", 0, 1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
