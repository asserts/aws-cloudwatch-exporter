/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.config.ScrapeConfig;
import ai.asserts.aws.cloudwatch.config.ScrapeConfigProvider;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesRequest;
import software.amazon.awssdk.services.config.model.ListDiscoveredResourcesResponse;
import software.amazon.awssdk.services.config.model.ResourceIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;

@Component
@Slf4j
public class ResourceExporter extends Collector implements MetricProvider {
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private final ResourceMapper resourceMapper;
    private final MetricNameUtil metricNameUtil;
    private final ResourceTagHelper resourceTagHelper;
    private final AccountIDProvider accountIDProvider;
    private volatile List<MetricFamilySamples> metrics = new ArrayList<>();

    public ResourceExporter(ScrapeConfigProvider scrapeConfigProvider,
                            AWSClientProvider awsClientProvider,
                            RateLimiter rateLimiter,
                            MetricSampleBuilder sampleBuilder, ResourceMapper resourceMapper,
                            MetricNameUtil metricNameUtil,
                            ResourceTagHelper resourceTagHelper,
                            AccountIDProvider accountIDProvider) {
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.resourceMapper = resourceMapper;
        this.metricNameUtil = metricNameUtil;
        this.resourceTagHelper = resourceTagHelper;
        this.accountIDProvider = accountIDProvider;
    }

    @Override
    public void update() {
        try {
            List<Sample> samples = new ArrayList<>();
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
            Set<String> discoverResourceTypes = scrapeConfig.getDiscoverResourceTypes();
            if (discoverResourceTypes.size() > 0) {
                scrapeConfig.getRegions().forEach(region -> {
                    log.info("Discovering resources in region {}", region);
                    try (ConfigClient configClient = awsClientProvider.getConfigClient(region)) {
                        discoverResourceTypes.forEach(resourceType ->
                                samples.addAll(getResources(region, configClient, resourceType)));
                    }
                });
                List<MetricFamilySamples> latest = new ArrayList<>();
                latest.add(sampleBuilder.buildFamily(samples));
                metrics = latest;
            }
        } catch (Exception e) {
            log.error("Failed to build resource metric samples", e);
        }
    }

    private List<Sample> getResources(String region, ConfigClient configClient, String resourceType) {
        List<Sample> samples = new ArrayList<>();
        String[] nextToken = new String[]{null};
        try {
            do {
                ListDiscoveredResourcesResponse response = rateLimiter.doWithRateLimit(
                        "ConfigClient/listDiscoveredResources",
                        ImmutableSortedMap.of(
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "ConfigClient/listDiscoveredResources"
                        ),
                        () -> configClient.listDiscoveredResources(ListDiscoveredResourcesRequest.builder()
                                .includeDeletedResources(false)
                                .nextToken(nextToken[0])
                                .resourceType(resourceType)
                                .build()));


                if (response.hasResourceIdentifiers()) {
                    List<String> resourceNames = response.resourceIdentifiers().stream()
                            .map(ResourceIdentifier::resourceName)
                            .collect(Collectors.toList());
                    Map<String, Resource> resourceByName = resourceTagHelper.getResourcesWithTag(
                            region, resourceType, resourceNames);
                    response.resourceIdentifiers().forEach(rI -> {
                        SortedMap<String, String> labels = new TreeMap<>();
                        labels.put(SCRAPE_REGION_LABEL, region);
                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountIDProvider.getAccountId());
                        String nameOrId = Optional.ofNullable(rI.resourceName()).orElse(rI.resourceId());
                        log.debug("Discovered resource {}-{}", rI.resourceType().toString(), nameOrId);
                        labels.put("aws_resource_type", rI.resourceType().toString());

                        Optional<Resource> arnResource = resourceMapper.map(rI.resourceId());
                        addBasicLabels(labels, rI, nameOrId, arnResource);
                        addTagLabels(resourceByName, labels, rI, arnResource);
                        Sample sample = sampleBuilder.buildSingleSample("aws_resource", labels, 1.0D);
                        samples.add(sample);
                    });
                }
                nextToken[0] = response.nextToken();
            } while (nextToken[0] != null);
        } catch (Exception e) {
            log.error("Failed to discover resources", e);
        }
        return samples;
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @VisibleForTesting
    void addBasicLabels(SortedMap<String, String> labels, ResourceIdentifier rI, String idOrName,
                        Optional<Resource> arnResource) {
        if (arnResource.isPresent()) {
            arnResource.ifPresent(resource -> {
                labels.put("job", resource.getName());
                if (resource.getAccount() != null) {
                    labels.put(SCRAPE_ACCOUNT_ID_LABEL, resource.getAccount());
                }
                switch (resource.getType()) {
                    case LoadBalancer:
                        labels.put("type", resource.getSubType());
                        break;
                    case ECSService:
                        labels.put("cluster", resource.getChildOf().getName());
                        break;
                    default:
                }
            });
        } else {
            labels.put("job", idOrName);
        }

        if (rI.resourceId() != null) {
            labels.put("id", rI.resourceId());
        }
        if (rI.resourceName() != null) {
            labels.put("name", rI.resourceName());
        }
    }

    @VisibleForTesting
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    void addTagLabels(Map<String, Resource> resourceByName,
                      SortedMap<String, String> labels,
                      ResourceIdentifier rI, Optional<Resource> arnResource) {
        Stream.of(rI.resourceName(), rI.resourceId())
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(key -> {
                    if (resourceByName.containsKey(key)) {
                        resourceByName.get(key).addTagLabels(labels, metricNameUtil);
                    }
                });

        arnResource.ifPresent(res -> {
            if (resourceByName.containsKey(res.getName())) {
                resourceByName.get(res.getName()).addTagLabels(labels, metricNameUtil);
            }
        });
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metrics;
    }
}
