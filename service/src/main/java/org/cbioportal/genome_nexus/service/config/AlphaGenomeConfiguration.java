package org.cbioportal.genome_nexus.service.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cbioportal.genome_nexus.component.annotation.Grch38ReferenceSequenceResolver;
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
 * {@code .fai} alongside). When unset, a fallback resolver answers every
 * lookup with {@code N}, so every REF comparison safely mismatches and no
 * variant is scored — the application still boots.
 */
@Configuration
public class AlphaGenomeConfiguration
{
    private static final Log LOG = LogFactory.getLog(AlphaGenomeConfiguration.class);

    @Bean
    public Grch38ReferenceSequenceResolver grch38ReferenceSequenceResolver(
        @Value("${grch38.fasta.path:}") String fastaPath)
    {
        if (fastaPath != null && !fastaPath.trim().isEmpty() && new File(fastaPath).exists()) {
            try {
                return new IndexedFastaGrch38ReferenceSequenceResolver(new File(fastaPath));
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Could not open GRCh38 FASTA at " + fastaPath, e);
            }
        }
        LOG.warn("grch38.fasta.path is not configured: AlphaGenome normalization "
            + "will reject every variant until a GRCh38 reference is provided.");
        return new Grch38ReferenceSequenceResolver()
        {
            @Override
            public String extract(String chrom, int zeroBasedStart, int zeroBasedEndExclusive)
            {
                StringBuilder out = new StringBuilder();
                for (int i = zeroBasedStart; i < zeroBasedEndExclusive; i++) {
                    out.append('N');
                }
                return out.toString();
            }
        };
    }
}
