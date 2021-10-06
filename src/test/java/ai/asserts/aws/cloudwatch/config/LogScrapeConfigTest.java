/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.config;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LogScrapeConfigTest {
    private LogScrapeConfig logScrapeConfig;

    @BeforeEach
    public void setup() {
        logScrapeConfig = LogScrapeConfig.builder()
                .logFilterPattern("logFilter")
                .regexPattern("put (.+?) in (.+?) queue")
                .labels(ImmutableMap.of(
                        "message_type", "$1",
                        "queue_name", "$2"
                ))
                .sampleLogMessage("put OrderRequest in Request queue")
                .sampleExpectedLabels(ImmutableMap.of(
                        "d_message_type", "OrderRequest",
                        "d_queue_name", "Request"
                ))
                .build();
    }

    @Test
    void initialize() {
        logScrapeConfig.initalize();
        assertTrue(logScrapeConfig.isValid());
    }

    @Test
    void extractLabels_Match() {
        logScrapeConfig.initalize();
        assertTrue(logScrapeConfig.isValid());
        assertEquals(
                ImmutableMap.of(
                        "d_message_type", "CancellationRequest",
                        "d_queue_name", "Cancellation"
                ),
                logScrapeConfig.extractLabels("put CancellationRequest in Cancellation queue")
        );
    }

    @Test
    void extractLabels_no_Match() {
        logScrapeConfig.initalize();
        assertTrue(logScrapeConfig.isValid());
        assertEquals(
                ImmutableMap.of(),
                logScrapeConfig.extractLabels("put CancellationRequest in Cancellation QUEUE")
        );
    }
}
