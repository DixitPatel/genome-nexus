package org.cbioportal.genome_nexus.service.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cbioportal.genome_nexus.component.annotation.Grch38ReferenceSequenceResolver;
import org.cbioportal.genome_nexus.component.annotation.HttpRangeGrch38ReferenceSequenceResolver;
import org.cbioportal.genome_nexus.component.annotation.IndexedFastaGrch38ReferenceSequenceResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * W3: exposes the GRCh38 reference sequence resolver that
 * {@code AlphaGenomeVariantNormalizer} needs for indel anchoring and the
 * mandatory REF check.
 *
 * <p>Set {@code grch38.fasta.path} to a local indexed GRCh38 FASTA (with its
 * {@code .fai} index alongside). Otherwise slices are fetched on demand over
 * HTTP {@code Range} requests against {@code grch38.fasta.url} plus its
 * {@code .fai} index ({@code grch38.fasta.fai-url}), defaulting to the GCS
 * reference the AlphaGenome SDK uses — no multi-gigabyte download required.
 */
@Configuration
public class AlphaGenomeConfiguration
{
    private static final Log LOG = LogFactory.getLog(AlphaGenomeConfiguration.class);

    @Bean
    public Grch38ReferenceSequenceResolver grch38ReferenceSequenceResolver(
        @Value("${grch38.fasta.path:}") String fastaPath,
        @Value("${grch38.fasta.url:https://storage.googleapis.com/alphagenome/reference/gencode/hg38/GRCh38.p13.genome.fa}")
        String fastaUrl,
        @Value("${grch38.fasta.fai-url:https://storage.googleapis.com/alphagenome/reference/gencode/hg38/GRCh38.p13.genome.fa.fai}")
        String faiUrl)
    {
        if (fastaPath != null && !fastaPath.trim().isEmpty() && new File(fastaPath).exists()) {
            try {
                return new IndexedFastaGrch38ReferenceSequenceResolver(new File(fastaPath));
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Could not open GRCh38 FASTA at " + fastaPath, e);
            }
        }
        LOG.info("Using remote GRCh38 slices from " + fastaUrl);
        try {
            return new HttpRangeGrch38ReferenceSequenceResolver(fastaUrl, faiUrl);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Could not load remote GRCh38 index from " + faiUrl, e);
        }
    }
}
