/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.MetricNameUtil;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceMapper;
import ai.asserts.aws.resource.ResourceTagHelper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import io.micrometer.core.instrument.util.StringUtils;
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
import static ai.asserts.aws.resource.ResourceType.AutoScalingGroup;
import static io.micrometer.core.instrument.util.StringUtils.isNotEmpty;
import static org.springframework.util.CollectionUtils.isEmpty;

@Component
@Slf4j
public class ResourceExporter extends Collector implements MetricProvider {
    private final AccountProvider accountProvider;
    private final ScrapeConfigProvider scrapeConfigProvider;
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final MetricSampleBuilder sampleBuilder;
    private final ResourceMapper resourceMapper;
    private final MetricNameUtil metricNameUtil;
    private final ResourceTagHelper resourceTagHelper;
    private volatile List<MetricFamilySamples> metrics = new ArrayList<>();

    public ResourceExporter(AccountProvider accountProvider,
                            ScrapeConfigProvider scrapeConfigProvider,
                            AWSClientProvider awsClientProvider,
                            RateLimiter rateLimiter,
                            MetricSampleBuilder sampleBuilder, ResourceMapper resourceMapper,
                            MetricNameUtil metricNameUtil,
                            ResourceTagHelper resourceTagHelper) {
        this.accountProvider = accountProvider;
        this.scrapeConfigProvider = scrapeConfigProvider;
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.sampleBuilder = sampleBuilder;
        this.resourceMapper = resourceMapper;
        this.metricNameUtil = metricNameUtil;
        this.resourceTagHelper = resourceTagHelper;
    }

    @Override
    public void update() {
        log.info("Exporting resources");
        try {
            List<Sample> samples = new ArrayList<>();
            ScrapeConfig scrapeConfig = scrapeConfigProvider.getScrapeConfig();
            Set<String> discoverResourceTypes = scrapeConfig.getDiscoverResourceTypes();
            if (!isEmpty(discoverResourceTypes)) {
                accountProvider.getAccounts().forEach(account -> {
                    String accountId = account.getAccountId();
                    account.getRegions().forEach(region -> {
                        log.info("Discovering resources in account {} region {}", accountId, region);
                        try {
                            ConfigClient configClient = awsClientProvider.getConfigClient(region, account);
                            discoverResourceTypes.forEach(resourceType ->
                                    samples.addAll(getResources(account, region, configClient, resourceType)));
                        } catch (Exception e) {
                            log.error("Error " + accountId, e);
                        }
                    });
                });
                List<MetricFamilySamples> latest = new ArrayList<>();
                if (!isEmpty(samples)) {
                    latest.add(sampleBuilder.buildFamily(samples));
                }
                metrics = latest;
            }
        } catch (Exception e) {
            log.error("Failed to build resource metric samples", e);
        }
    }

    private List<Sample> getResources(
            AWSAccount account, String region,
            ConfigClient configClient, String resourceType) {
        List<Sample> samples = new ArrayList<>();
        String[] nextToken = new String[]{null};
        try {
            do {
                ListDiscoveredResourcesResponse response = rateLimiter.doWithRateLimit(
                        "ConfigClient/listDiscoveredResources",
                        ImmutableSortedMap.of(
                                SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId(),
                                SCRAPE_REGION_LABEL, region,
                                SCRAPE_OPERATION_LABEL, "ConfigClient/listDiscoveredResources"
                        ),
                        () -> configClient.listDiscoveredResources(ListDiscoveredResourcesRequest.builder()
                                .includeDeletedResources(false)
                                .nextToken(nextToken[0])
                                .resourceType(resourceType)
                                .build()));


                if (!isEmpty(response.resourceIdentifiers())) {
                    List<String> resourceNames = response.resourceIdentifiers().stream()
                            .map(ResourceIdentifier::resourceName)
                            .filter(StringUtils::isNotEmpty)
                            .collect(Collectors.toList());
                    Map<String, Resource> resourceByName = resourceTagHelper.getResourcesWithTag(
                            account, region, resourceType, resourceNames);
                    response.resourceIdentifiers().forEach(rI -> {
                        SortedMap<String, String> labels = new TreeMap<>();
                        labels.put(SCRAPE_REGION_LABEL, region);
                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, account.getAccountId());
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
    void addBasicLabels(SortedMap<String, String> labels, ResourceIdentifier rI, String nameOrId,
                        Optional<Resource> arnResource) {
        boolean hasId = rI.resourceId() != null;
        boolean hasName = rI.resourceName() != null;
        if (hasId) {
            labels.put("id", rI.resourceId());
        }
        if (hasName) {
            labels.put("name", rI.resourceName());
            labels.put("job", rI.resourceName());
        }
        arnResource.ifPresent(resource -> {
            // If name and job are not set
            String nameFromResource = resource.getName();
            labels.putIfAbsent("job", nameFromResource);
            labels.putIfAbsent("name", nameFromResource);

            // If the arn has an id, we prefer it as the id
            if (isNotEmpty(resource.getId())) {
                labels.put("id", resource.getId());
            } else if (hasName && !rI.resourceName().equals(nameFromResource)) {
                labels.put("id", nameFromResource);
            }

            if (labels.containsKey("id") && isIdValueUrlOrARN(labels)) {
                labels.remove("id");
            }

            if (isNotEmpty(resource.getAccount())) {
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

        // If there is no resource, we set name or id as job
        labels.putIfAbsent("job", nameOrId);
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
                Resource resWithTags = resourceByName.get(res.getName());
                resWithTags.addTagLabels(labels, metricNameUtil);
                if (AutoScalingGroup.equals(res.getType()) && resWithTags.getSubType() != null) {
                    labels.put("subtype", resWithTags.getSubType());
                }
            }
        });
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metrics;
    }

    private boolean isIdValueUrlOrARN(SortedMap<String, String> labels) {
        return labels.get("id").contains("arn:aws") || labels.get("id").contains("https://");
    }
}
