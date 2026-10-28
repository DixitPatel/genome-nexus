package org.cbioportal.genome_nexus.service.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cbioportal.genome_nexus.component.annotation.AlphaGenomeVariantNormalizer;
import org.cbioportal.genome_nexus.component.annotation.NormalizedAlphaGenomeVariant;
import org.cbioportal.genome_nexus.model.AlphaGenome;
import org.cbioportal.genome_nexus.model.VariantAnnotation;
import org.cbioportal.genome_nexus.service.AlphaGenomeService;
import org.cbioportal.genome_nexus.service.cached.CachedAlphaGenomeFetcher;
import org.cbioportal.genome_nexus.service.exception.AlphaGenomeNotFoundException;
import org.cbioportal.genome_nexus.service.exception.AlphaGenomeWebServiceException;
import org.cbioportal.genome_nexus.service.exception.ResourceMappingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * W3: resolves the cached AlphaGenome annotation for Genome Nexus
 * annotations. Normalization (W1) runs first; variants that fail it return
 * {@code null} and are never scored or cached.
 */
@Service
public class AlphaGenomeServiceImpl implements AlphaGenomeService
{
    private static final Log LOG = LogFactory.getLog(AlphaGenomeServiceImpl.class);

    private final CachedAlphaGenomeFetcher cachedAlphaGenomeFetcher;
    private final AlphaGenomeVariantNormalizer normalizer;

    @Autowired
    public AlphaGenomeServiceImpl(CachedAlphaGenomeFetcher cachedAlphaGenomeFetcher,
                                  AlphaGenomeVariantNormalizer normalizer)
    {
        this.cachedAlphaGenomeFetcher = cachedAlphaGenomeFetcher;
        this.normalizer = normalizer;
    }

    @Override
    public AlphaGenome getAlphaGenomeByAnnotation(VariantAnnotation annotation)
        throws AlphaGenomeNotFoundException, AlphaGenomeWebServiceException
    {
        if (annotation == null) {
            return null;
        }
        Optional<NormalizedAlphaGenomeVariant> normalized = normalizer.normalize(annotation);
        if (!normalized.isPresent()) {
            return null;
        }
        Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();
        bodies.put(annotation.getVariant(), buildRequestBody(annotation.getVariant(), normalized.get()));
        try {
            List<AlphaGenome> results = cachedAlphaGenomeFetcher.fetchAndCache(bodies);
            if (results.isEmpty()) {
                throw new AlphaGenomeNotFoundException(annotation.getVariant());
            }
            return results.get(0);
        } catch (ResourceMappingException e) {
            throw new AlphaGenomeWebServiceException(e.getMessage());
        } catch (HttpClientErrorException e) {
            throw new AlphaGenomeWebServiceException(e.getResponseBodyAsString(), e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new AlphaGenomeWebServiceException(e.getMessage());
        }
    }

    @Override
    public List<AlphaGenome> getAlphaGenomeByAnnotation(List<VariantAnnotation> annotations)
        throws AlphaGenomeWebServiceException
    {
        Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();
        for (VariantAnnotation annotation : annotations) {
            if (annotation == null) {
                continue;
            }
            Optional<NormalizedAlphaGenomeVariant> normalized = normalizer.normalize(annotation);
            if (normalized.isPresent()) {
                bodies.put(annotation.getVariant(),
                    buildRequestBody(annotation.getVariant(), normalized.get()));
            }
        }
        if (bodies.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return cachedAlphaGenomeFetcher.fetchAndCache(bodies);
        } catch (ResourceMappingException e) {
            throw new AlphaGenomeWebServiceException(e.getMessage());
        } catch (HttpClientErrorException e) {
            throw new AlphaGenomeWebServiceException(e.getResponseBodyAsString(), e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new AlphaGenomeWebServiceException(e.getMessage());
        }
    }

    private Map<String, Object> buildRequestBody(String originalVariant,
                                                   NormalizedAlphaGenomeVariant normalized)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variant", originalVariant);
        body.put("chromosome", normalized.getChrom());
        body.put("position", normalized.getPos());
        body.put("reference_allele", normalized.getRef());
        body.put("alternate_allele", normalized.getAlt());
        body.put("canonical_gene_id", normalized.getGeneId());
        body.put("canonical_gene", normalized.getGeneSymbol());
        return body;
    }
}
