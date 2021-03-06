/*
 *  Copyright © 2022.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.ResourceMapper;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class SNSTopicExporter extends Collector implements InitializingBean {
    public final CollectorRegistry collectorRegistry;
    private final AccountProvider accountProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private final ResourceMapper resourceMapper;
    private volatile List<MetricFamilySamples> metricFamilySamples = new ArrayList<>();

    public SNSTopicExporter(
            AccountProvider accountProvider, AWSClientProvider awsClientProvider, CollectorRegistry collectorRegistry,
            RateLimiter rateLimiter, MetricSampleBuilder sampleBuilder, ResourceMapper resourceMapper) {
        this.accountProvider = accountProvider;
        this.awsClientProvider = awsClientProvider;
        this.collectorRegistry = collectorRegistry;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.resourceMapper = resourceMapper;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        register(collectorRegistry);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metricFamilySamples;
    }

    public void update() {
        log.info("Exporting SNS Topic Resources");
        List<MetricFamilySamples> newFamily = new ArrayList<>();
        List<MetricFamilySamples.Sample> samples = new ArrayList<>();
        accountProvider.getAccounts().forEach(account -> account.getRegions().forEach(region -> {
            try {
                SnsClient client = awsClientProvider.getSnsClient(region, account);
                String api = "SnsClient/listTopics";
                ListTopicsResponse resp = rateLimiter.doWithRateLimit(
                        api, ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, api
                        ), client::listTopics);
                if (resp.hasTopics()) {
                    samples.addAll(resp.topics().stream()
                            .map(topic -> resourceMapper.map(topic.topicArn()))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .map(topicResource -> {
                                Map<String, String> labels = new TreeMap<>();
                                labels.put(SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId());
                                labels.put(SCRAPE_REGION_LABEL, region);
                                labels.put("aws_resource_type", "AWS::SNS::Topic");
                                labels.put("job", topicResource.getName());
                                labels.put("name", topicResource.getName());
                                labels.put("namespace", "AWS/SNS");
                                return sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                            })
                            .collect(Collectors.toList()));
                }
            } catch (Exception e) {
                log.error("Failed to update", e);
            }
        }));
        newFamily.add(sampleBuilder.buildFamily(samples));
        metricFamilySamples = newFamily;
    }
}
