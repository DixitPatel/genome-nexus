package org.cbioportal.genome_nexus.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Common pipeline debug logger for AlphaGenome integration.
 * Outputs to slf4j/commons-logging and appends structured entries to
 * workspace/logs/alphagenome_pipeline_debug.log.
 */
public class AlphaGenomePipelineDebugLogger
{
    private static final Log LOG = LogFactory.getLog(AlphaGenomePipelineDebugLogger.class);
    private static final String COMMON_LOG_PATH =
        "/Users/dixitpatel/Documents/Personal-Dev-Workspace/Meta_hackathon/workspace/logs/alphagenome_pipeline_debug.log";
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static synchronized void log(String phase, String message)
    {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String formattedLine = String.format("[%s] [GN-BACKEND] [%s] %s", timestamp, phase, message);

        LOG.info(String.format("[%s] %s", phase, message));
        System.out.println(formattedLine);

        try {
            File logFile = new File(COMMON_LOG_PATH);
            if (!logFile.getParentFile().exists()) {
                logFile.getParentFile().mkdirs();
            }
            try (FileWriter fw = new FileWriter(logFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println(formattedLine);
            }
        } catch (Exception ignored) {
            // Safe fallback: never disrupt application flow
        }
    }
}
