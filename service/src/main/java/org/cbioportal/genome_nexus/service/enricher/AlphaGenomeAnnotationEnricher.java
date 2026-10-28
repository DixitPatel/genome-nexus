package org.cbioportal.genome_nexus.service.enricher;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cbioportal.genome_nexus.model.AlphaGenome;
import org.cbioportal.genome_nexus.model.AlphaGenomeAnnotation;
import org.cbioportal.genome_nexus.model.VariantAnnotation;
import org.cbioportal.genome_nexus.service.AlphaGenomeService;
import org.cbioportal.genome_nexus.service.exception.AlphaGenomeNotFoundException;
import org.cbioportal.genome_nexus.service.exception.AlphaGenomeWebServiceException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AlphaGenomeAnnotationEnricher extends BaseAnnotationEnricher
{
    private static final Log LOG = LogFactory.getLog(AlphaGenomeAnnotationEnricher.class);

    /**
     * Provenance of the annotation payload: the model and SDK it was scored with.
     */
    public static final String LICENSE = "https://github.com/google-deepmind/alphagenome";

    private AlphaGenomeService alphaGenomeService;

    public AlphaGenomeAnnotationEnricher(
        String id,
        AlphaGenomeService alphaGenomeService
    ) {
        super(id);
        this.alphaGenomeService = alphaGenomeService;
    }

    @Override
    public void enrich(VariantAnnotation annotation) {
        if (annotation != null)
        {
            AlphaGenome alphaGenome = null;

            try {
                alphaGenome = alphaGenomeService.getAlphaGenomeByAnnotation(annotation);
            } catch (AlphaGenomeWebServiceException e) {
                LOG.warn(e.getMessage());
            } catch (AlphaGenomeNotFoundException e) {
                // fail silently for this variant annotation
            }

            this.enrichAnnotationWithAlphaGenome(annotation, alphaGenome);
        }
    }

    @Override
    public void enrich(List<VariantAnnotation> annotations) {
        try {
            List<AlphaGenome> alphaGenomes = alphaGenomeService.getAlphaGenomeByAnnotation(annotations);
            Map<String, AlphaGenome> variantToAlphaGenomeMap =
                alphaGenomes.stream().collect(Collectors.toMap(AlphaGenome::getVariant, v -> v));

            for (VariantAnnotation annotation: annotations) {
                this.enrichAnnotationWithAlphaGenome(annotation,
                    variantToAlphaGenomeMap.get(annotation.getVariant()));
            }
        } catch (AlphaGenomeWebServiceException e) {
            LOG.warn(e.getMessage());
        }
    }

    private void enrichAnnotationWithAlphaGenome(
        VariantAnnotation annotation,
        AlphaGenome alphaGenome
    ) {
        if (alphaGenome != null)
        {
            AlphaGenomeAnnotation alphaGenomeAnnotation = new AlphaGenomeAnnotation();
            alphaGenomeAnnotation.setLicense(LICENSE);
            alphaGenomeAnnotation.setAnnotation(alphaGenome);

            annotation.setAlphaGenomeAnnotation(alphaGenomeAnnotation);
        }
    }
}
