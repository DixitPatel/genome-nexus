package org.cbioportal.genome_nexus.service.cached;

import com.mongodb.DBObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cbioportal.genome_nexus.model.AlphaGenome;
import org.cbioportal.genome_nexus.persistence.AlphaGenomeRepository;
import org.cbioportal.genome_nexus.service.exception.ResourceMappingException;
import org.cbioportal.genome_nexus.service.remote.AlphaGenomeDataFetcher;
import org.cbioportal.genome_nexus.service.transformer.ExternalResourceTransformer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * W3: cached sidecar fetcher. One variant per sidecar call
 * ({@code max_page_size = 1}); uncached chunks are fetched concurrently
 * through a bounded pool instead of the base class sequential loop.
 *
 * <p>Sidecar request bodies carry normalized coordinates that are not part of
 * the cache id, so callers stage them per call via
 * {@link #fetchAndCache(Map)}; {@code buildRequestBody} resolves them from a
 * thread-local. {@code _id} is always the echoed Genome Nexus variant string.
 */
@Component
public class CachedAlphaGenomeFetcher extends BaseCachedExternalResourceFetcher<AlphaGenome, AlphaGenomeRepository>
{
    private static final Log LOG = LogFactory.getLog(CachedAlphaGenomeFetcher.class);

    public static final String COLLECTION = "alphagenome.annotation";

    private final ExecutorService fetchPool;
    private final ThreadLocal<Map<String, Map<String, Object>>> pendingBodies =
        new ThreadLocal<>();

    @Autowired
    public CachedAlphaGenomeFetcher(
        ExternalResourceTransformer<AlphaGenome> transformer,
        AlphaGenomeRepository repository,
        AlphaGenomeDataFetcher fetcher,
        @Value("${alphagenome.sidecar.max_page_size:1}") Integer maxPageSize,
        @Value("${alphagenome.sidecar.max_concurrency:4}") Integer maxConcurrency
    ) {
        super(COLLECTION, repository, AlphaGenome.class, fetcher, transformer, maxPageSize);
        this.fetchPool = Executors.newFixedThreadPool(
            Math.max(1, maxConcurrency),
            runnable -> {
                Thread thread = new Thread(runnable, "alphagenome-sidecar-fetch");
                thread.setDaemon(true);
                return thread;
            });
    }

    /**
     * Fetches the given variants, using the staged sidecar request body for
     * each cache miss. The key of each entry is the Genome Nexus variant id
     * (also the MongoDB {@code _id}); the value is the {@code POST /score}
     * JSON body built from the normalized GRCh38 variant and canonical gene.
     */
    public List<AlphaGenome> fetchAndCache(Map<String, Map<String, Object>> requestBodies)
        throws ResourceMappingException
    {
        pendingBodies.set(new LinkedHashMap<>(requestBodies));
        try {
            return fetchAndCache(new ArrayList<>(requestBodies.keySet()));
        }
        finally {
            pendingBodies.remove();
        }
    }

    @Override
    protected Object buildRequestBody(Set<String> ids)
    {
        Map<String, Map<String, Object>> bodies = pendingBodies.get();
        if (bodies == null) {
            LOG.warn("No staged sidecar request bodies for ids: " + ids);
            return new LinkedHashMap<String, Object>();
        }
        if (ids.size() == 1) {
            Map<String, Object> body = bodies.get(ids.iterator().next());
            if (body != null) {
                return body;
            }
        }
        LOG.warn("No staged sidecar request body for ids: " + ids);
        return new LinkedHashMap<String, Object>();
    }

    @Override
    protected String extractId(AlphaGenome instance)
    {
        return instance.getVariant();
    }

    @Override
    protected String extractId(DBObject dbObject)
    {
        Object variant = dbObject.get("variant");
        return variant == null ? (String) dbObject.get("_id") : variant.toString();
    }

    /**
     * Bounded-pool replacement for the base class sequential chunk loop.
     * HTTP fetches run concurrently; normalization, transformation, and cache
     * writes stay sequential in chunk order, exactly like the base class.
     */
    @Override
    protected void fetchAndCache(Set<String> needToFetch,
                                 Map<String, AlphaGenome> idToInstance,
                                 boolean saveValues) throws ResourceMappingException
    {
        List<Set<String>> chunks = new ArrayList<>(this.generateChunks(needToFetch));
        List<Future<DBObject>> futures = new ArrayList<>();
        for (Set<String> chunk : chunks) {
            final Set<String> ids = new LinkedHashSet<>(chunk);
            final Map<String, Map<String, Object>> bodies =
                pendingBodies.get() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(pendingBodies.get());
            Callable<DBObject> task = () -> {
                pendingBodies.set(bodies);
                try {
                    return this.fetcher.fetchRawValue(this.buildRequestBody(ids));
                }
                finally {
                    pendingBodies.remove();
                }
            };
            futures.add(fetchPool.submit(task));
        }

        for (int i = 0; i < chunks.size(); i++) {
            DBObject rawValue = null;
            try {
                rawValue = futures.get(i).get();
            } catch (Exception e) {
                LOG.error("AlphaGenome sidecar fetch failed for " + chunks.get(i) + ": "
                    + e.getLocalizedMessage());
            }
            if (rawValue == null) {
                continue;
            }
            try {
                rawValue = this.normalizeResponse(rawValue);
                List<AlphaGenome> fetched = this.transformer.transform(rawValue, this.type);
                fetched.forEach(t -> idToInstance.put(this.extractId(t), t));
                if (saveValues) {
                    this.saveToDb(rawValue);
                }
            } catch (Exception e) {
                LOG.error("AlphaGenome sidecar response handling failed for " + chunks.get(i)
                    + ": " + e.getLocalizedMessage());
            }
        }
    }
}
