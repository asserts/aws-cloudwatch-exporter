/*
 * Copyright © 2021
 * Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.lambda;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.cloudwatch.config.NamespaceConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.cloudwatch.prometheus.GaugeExporter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.TagFilterResourceProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.EventSourceMappingConfiguration;
import software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsResponse;

import java.time.Instant;
import java.util.Optional;

import static org.easymock.EasyMock.expect;

public class LambdaEventSourceExporterTest extends EasyMockSupport {
    private LambdaClient lambdaClient;
    private MetricNameUtil metricNameUtil;
    private GaugeExporter gaugeExporter;
    private ResourceMapper resourceMapper;
    private NamespaceConfig namespaceConfig;
    private TagFilterResourceProvider tagFilterResourceProvider;
    private LambdaEventSourceExporter testClass;
    private Resource fnResource;
    private Resource sourceResource;
    private Instant now;

    @BeforeEach
    public void setup() {
        metricNameUtil = mock(MetricNameUtil.class);
        gaugeExporter = mock(GaugeExporter.class);
        lambdaClient = mock(LambdaClient.class);
        resourceMapper = mock(ResourceMapper.class);
        now = Instant.now();
        fnResource = mock(Resource.class);
        sourceResource = mock(Resource.class);
        tagFilterResourceProvider = mock(TagFilterResourceProvider.class);

        namespaceConfig = mock(NamespaceConfig.class);
        expect(namespaceConfig.getName()).andReturn("lambda").anyTimes();
        ScrapeConfigProvider scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(
                ScrapeConfig.builder()
                        .regions(ImmutableSet.of("region1"))
                        .namespaces(ImmutableList.of(namespaceConfig))
                        .build()
        ).anyTimes();

        AWSClientProvider awsClientProvider = mock(AWSClientProvider.class);
        expect(awsClientProvider.getLambdaClient("region1")).andReturn(lambdaClient).anyTimes();

        testClass = new LambdaEventSourceExporter(scrapeConfigProvider, awsClientProvider,
                metricNameUtil, gaugeExporter, resourceMapper, tagFilterResourceProvider) {
            @Override
            Instant now() {
                return now;
            }
        };
    }

    @Test
    public void exportEventSourceMappings() {
        ImmutableSortedMap<String, String> fn1Labels = ImmutableSortedMap.of(
                "region", "region1",
                "lambda_function", "fn1"
        );

        ImmutableSortedMap<String, String> fn2Labels = ImmutableSortedMap.of(
                "region", "region1",
                "lambda_function", "fn2"
        );

        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andReturn(ImmutableSet.of(fnResource, fnResource));

        expect(lambdaClient.listEventSourceMappings()).andReturn(
                ListEventSourceMappingsResponse.builder()
                        .eventSourceMappings(ImmutableList.of(
                                EventSourceMappingConfiguration.builder()
                                        .functionArn("fn1_arn")
                                        .eventSourceArn("queue_arn")
                                        .build(),
                                EventSourceMappingConfiguration.builder()
                                        .functionArn("fn2_arn")
                                        .eventSourceArn("table_arn")
                                        .build()
                        ))
                        .build()
        );

        String help = "Metric with lambda event source information";

        expect(metricNameUtil.getMetricPrefix("AWS/Lambda")).andReturn("aws_lambda").anyTimes();

        expect(fnResource.getName()).andReturn("fn1");
        expect(resourceMapper.map("fn1_arn")).andReturn(Optional.of(fnResource));
        expect(resourceMapper.map("queue_arn")).andReturn(Optional.of(sourceResource));
        fnResource.addTagLabels(fn1Labels, metricNameUtil);
        sourceResource.addLabels(fn1Labels, "event_source");
        gaugeExporter.exportMetric("aws_lambda_event_source", help,
                fn1Labels,
                now, 1.0D);

        expect(fnResource.getName()).andReturn("fn2");
        expect(resourceMapper.map("fn2_arn")).andReturn(Optional.of(fnResource));
        expect(resourceMapper.map("table_arn")).andReturn(Optional.of(sourceResource));
        fnResource.addTagLabels(fn2Labels, metricNameUtil);
        sourceResource.addLabels(fn2Labels, "event_source");
        gaugeExporter.exportMetric("aws_lambda_event_source", help,
                fn2Labels,
                now, 1.0D);

        replayAll();
        testClass.run();
        verifyAll();
    }

    @Test
    public void exportEventSourceMappings_Exception() {
        expect(tagFilterResourceProvider.getFilteredResources("region1", namespaceConfig))
                .andThrow(new RuntimeException());

        replayAll();
        testClass.run();
        verifyAll();
    }
}
