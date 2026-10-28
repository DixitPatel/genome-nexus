package org.cbioportal.genome_nexus.component.annotation;

import htsjdk.samtools.liftover.LiftOver;
import htsjdk.samtools.util.Interval;

import org.cbioportal.genome_nexus.model.TranscriptConsequence;
import org.cbioportal.genome_nexus.model.VariantAnnotation;
import org.cbioportal.genome_nexus.util.GenomicLocationUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * W1: normalizes a Genome Nexus {@code VariantAnnotation} into GRCh38,
 * AlphaGenome-ready coordinates.
 *
 * <p>Never reads {@code VariantAnnotation.getHgvsg()}: VEP 3'-shifts hgvsg on
 * repetitive indels (e.g. SMAD4 {@code c.1520_1521del}). All coordinates come
 * from {@code seqRegionName}, {@code start}, {@code end}, and
 * {@code alleleString}.
 *
 * <p>Step 1 (pre-filter) and Step 2 ({@code GRCh37 -> GRCh38} liftover) run
 * first. Rejected variants return {@code Optional.empty()} and must never be
 * scored or cached. Step 3 ports the {@code alphagenome} SDK
 * {@code normalize_variant} left-alignment (Tan et al. 2015) to convert MAF
 * {@code -} alleles into VCF anchor-base form, and Step 4 verifies
 * {@code REF} against GRCh38 and extracts the canonical gene.
 */
@Component
public class AlphaGenomeVariantNormalizer
{
    public static final String CHAIN_RESOURCE = "liftover/hg19ToHg38.over.chain.gz";

    private static final Pattern ALLELE_PATTERN = Pattern.compile("^[ACGT-]+$");

    private static final Set<String> VALID_CHROMOSOMES = new HashSet<>(Arrays.asList(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
        "11", "12", "13", "14", "15", "16", "17", "18", "19", "20",
        "21", "22", "X", "Y"
    ));

    private final LiftOver liftOver;
    private final Grch38ReferenceSequenceResolver referenceResolver;
    private final CanonicalTranscriptResolver canonicalTranscriptResolver;

    @Autowired
    public AlphaGenomeVariantNormalizer(CanonicalTranscriptResolver canonicalTranscriptResolver)
    {
        this(loadDefaultLiftOver(), null, canonicalTranscriptResolver);
    }

    /**
     * Package-visible for unit tests: inject a pre-configured liftover,
     * reference resolver, and canonical transcript resolver. A {@code null}
     * reference resolver fails fast on any call, since indel anchoring and
     * the mandatory {@code REF} check cannot run without GRCh38 sequence.
     */
    AlphaGenomeVariantNormalizer(
        LiftOver liftOver,
        Grch38ReferenceSequenceResolver referenceResolver,
        CanonicalTranscriptResolver canonicalTranscriptResolver
    ) {
        if (liftOver == null) {
            throw new IllegalArgumentException("liftOver must not be null");
        }
        this.liftOver = liftOver;
        this.referenceResolver = referenceResolver;
        this.canonicalTranscriptResolver = canonicalTranscriptResolver;
    }

    /**
     * Applies pre-filtering and liftover. Returns {@code Optional.empty()}
     * for any variant that must not reach scoring or caching.
     */
    public Optional<NormalizedAlphaGenomeVariant> normalize(VariantAnnotation annotation)
    {
        // ---- Step 1: pre-filter ----
        if (annotation == null
            || !Boolean.TRUE.equals(annotation.isSuccessfullyAnnotated())
            || annotation.getErrorMessage() != null) {
            return Optional.empty();
        }
        if (annotation.getSeqRegionName() == null
            || annotation.getStart() == null
            || annotation.getEnd() == null
            || annotation.getAlleleString() == null
            || annotation.getAlleleString().isEmpty()) {
            return Optional.empty();
        }

        String chrom = normalizeChromosome(annotation.getSeqRegionName());
        if (chrom == null) {
            return Optional.empty();
        }

        String[] alleles = annotation.getAlleleString().toUpperCase().split("/", -1);
        if (alleles.length != 2) {
            return Optional.empty();
        }
        String ref = alleles[0].trim();
        String alt = alleles[1].trim();
        if (ref.isEmpty()
            || alt.isEmpty()
            || ref.equals(alt)
            || !ALLELE_PATTERN.matcher(ref).matches()
            || !ALLELE_PATTERN.matcher(alt).matches()) {
            return Optional.empty();
        }

        if (annotation.getStrand() != null && annotation.getStrand() == -1) {
            if (!ref.equals("-")) {
                ref = GenomicLocationUtil.getReverseStrandAllele(ref);
            }
            if (!alt.equals("-")) {
                alt = GenomicLocationUtil.getReverseStrandAllele(alt);
            }
        }

        // ---- Step 2: GRCh37 -> GRCh38 liftover ----
        String assembly = annotation.getAssemblyName();
        boolean isGrch37 = assembly == null || assembly.equalsIgnoreCase("GRCh37");
        boolean isGrch38 = assembly != null && assembly.equalsIgnoreCase("GRCh38");
        if (!isGrch37 && !isGrch38) {
            return Optional.empty();
        }

        String contig = "chr" + chrom;
        int liftedStart;
        int liftedEnd;
        if (isGrch37) {
            // Insertions may be stored with start/end swapped (end = start - 1);
            // lift the covered span consistently.
            int low = Math.min(annotation.getStart(), annotation.getEnd());
            int high = Math.max(annotation.getStart(), annotation.getEnd());
            if (low < 1) {
                return Optional.empty();
            }
            Interval lifted = liftOver.liftOver(new Interval(contig, low, high));
            if (lifted == null || !contig.equals(lifted.getContig())) {
                return Optional.empty();
            }
            liftedStart = lifted.getStart();
            liftedEnd = lifted.getEnd();
        }
        else {
            liftedStart = annotation.getStart();
            liftedEnd = annotation.getEnd();
        }

        if (referenceResolver == null) {
            throw new IllegalStateException(
                "Grch38ReferenceSequenceResolver is not configured");
        }

        // ---- Step 3: VCF indel anchoring & left-alignment ----
        // Verbatim port of alphagenome genome.normalize_variant (Tan et al.
        // 2015): MAF "-" becomes the empty allele, SNVs pass through, and
        // every other allele runs the suffix-trim / left-extend / prefix-trim
        // loop against GRCh38 so indels gain a 1-bp anchor base.
        int pos;
        String vcfRef;
        String vcfAlt;
        if (!ref.equals("-") && !alt.equals("-") && ref.length() == 1 && alt.length() == 1) {
            pos = liftedStart;
            vcfRef = ref;
            vcfAlt = alt;
        }
        else {
            String seedRef = ref.equals("-") ? "" : ref;
            String seedAlt = alt.equals("-") ? "" : alt;
            // Deletions seed at the first deleted base; insertions seed at
            // the anchor coordinate Genome Nexus stores (min of start/end).
            int seedPos = ref.equals("-")
                ? Math.min(liftedStart, liftedEnd)
                : liftedStart;
            String[] normalizedAlleles = normalizeAlleles(contig, seedPos, seedRef, seedAlt);
            pos = Integer.parseInt(normalizedAlleles[0]);
            vcfRef = normalizedAlleles[1];
            vcfAlt = normalizedAlleles[2];
        }

        // ---- Step 4: GRCh38 REF check & canonical transcript extraction ----
        // AlphaGenome does not reject wrong REF bases, so W1 must: compare
        // case-insensitively and reject on any mismatch.
        String expectedRef = referenceResolver.extract(contig, pos - 1, pos - 1 + vcfRef.length());
        if (!expectedRef.equalsIgnoreCase(vcfRef)) {
            return Optional.empty();
        }

        String geneId = null;
        String geneSymbol = null;
        if (canonicalTranscriptResolver != null) {
            TranscriptConsequence canonical = canonicalTranscriptResolver.resolve(annotation);
            if (canonical != null) {
                geneId = stripGeneVersion(canonical.getGeneId());
                geneSymbol = canonical.getGeneSymbol();
            }
        }

        return Optional.of(
            new NormalizedAlphaGenomeVariant(contig, pos, vcfRef, vcfAlt, geneId, geneSymbol));
    }

    /**
     * Ports {@code alphagenome.data.genome.normalize_variant}. Returns a
     * 3-element array of {@code [position, referenceBases, alternateBases]}.
     * The seed follows MAF conventions ({@code ""} for {@code -}); the result
     * is VCF-style with at least 1 bp of anchor context.
     */
    private String[] normalizeAlleles(String chrom, int position, String ref, String alt)
    {
        int window = Math.max(ref.length(), alt.length());
        String genomeSequence = referenceResolver.extract(chrom, position - 1, position - 1 + window);
        String[] alleles = new String[]{genomeSequence, ref, alt};

        boolean finished = false;
        while (!finished) {
            int suffixLength = commonPrefixLength(reverse(alleles[0]), reverse(alleles[1]), reverse(alleles[2]));
            if (suffixLength > 0) {
                alleles[0] = alleles[0].substring(0, alleles[0].length() - suffixLength);
                alleles[1] = alleles[1].substring(0, alleles[1].length() - suffixLength);
                alleles[2] = alleles[2].substring(0, alleles[2].length() - suffixLength);
            }

            if (alleles[0].isEmpty() || alleles[1].isEmpty() || alleles[2].isEmpty()) {
                position -= 1;
                String base = referenceResolver.extract(chrom, position - 1, position);
                alleles[0] = base + alleles[0];
                alleles[1] = base + alleles[1];
                alleles[2] = base + alleles[2];
            }
            else {
                finished = suffixLength == 0;
            }
        }

        int shortest = Math.min(alleles[0].length(), Math.min(alleles[1].length(), alleles[2].length()));
        int maxPrefixLength = shortest - 1;
        int prefixLength = commonPrefixLength(alleles[0], alleles[1], alleles[2]);
        int trim = Math.min(maxPrefixLength, prefixLength);

        return new String[]{
            Integer.toString(position + trim),
            alleles[1].substring(trim),
            alleles[2].substring(trim)
        };
    }

    /** Mirrors the SDK {@code _prefix_length} helper (stops at shortest input). */
    private static int commonPrefixLength(String first, String second, String third)
    {
        String[] inputs = new String[]{first, second, third};
        int shortest = Math.min(first.length(), Math.min(second.length(), third.length()));
        int length = 0;
        while (length < shortest
            && inputs[0].charAt(length) == inputs[1].charAt(length)
            && inputs[0].charAt(length) == inputs[2].charAt(length)) {
            length++;
        }
        return length;
    }

    private static String reverse(String value)
    {
        return new StringBuilder(value).reverse().toString();
    }

    /** Strips any Ensembl version suffix (e.g. {@code ENSG....5} -> {@code ENSG...}). */
    private static String stripGeneVersion(String geneId)
    {
        if (geneId == null) {
            return null;
        }
        int dot = geneId.indexOf('.');
        return dot == -1 ? geneId : geneId.substring(0, dot);
    }

    /**
     * Inverse of {@code NotationConverter.chromosomeNormalizer} plus AlphaGenome
     * gating: strips any {@code chr} prefix, maps {@code 23 -> X} and
     * {@code 24 -> Y}, and rejects mitochondrial / unknown contigs.
     */
    private static String normalizeChromosome(String seqRegionName)
    {
        String chrom = seqRegionName.trim();
        if (chrom.length() >= 3 && chrom.regionMatches(true, 0, "chr", 0, 3)) {
            chrom = chrom.substring(3);
        }
        chrom = chrom.toUpperCase();
        if (chrom.equals("23")) {
            chrom = "X";
        }
        else if (chrom.equals("24")) {
            chrom = "Y";
        }
        if (chrom.equals("M") || chrom.equals("MT")) {
            return null;
        }
        if (!VALID_CHROMOSOMES.contains(chrom)) {
            return null;
        }
        return chrom;
    }

    /**
     * Loads the bundled chain file. Package-visible so tests exercise the
     * exact production loading path. The stream is staged to a temp file
     * because the file-backed htsjdk constructor (verified against the
     * gzipped chain) is used rather than the text-stream variant.
     */
    static LiftOver loadDefaultLiftOver()
    {
        InputStream chainStream = AlphaGenomeVariantNormalizer.class
            .getClassLoader()
            .getResourceAsStream(CHAIN_RESOURCE);
        if (chainStream == null) {
            throw new IllegalStateException(
                "Liftover chain file not found on classpath: " + CHAIN_RESOURCE);
        }
        try {
            File stagedChain = File.createTempFile("hg19ToHg38", ".over.chain.gz");
            stagedChain.deleteOnExit();
            Files.copy(chainStream, stagedChain.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return new LiftOver(stagedChain);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Could not stage liftover chain file: " + CHAIN_RESOURCE, e);
        }
    }
}
