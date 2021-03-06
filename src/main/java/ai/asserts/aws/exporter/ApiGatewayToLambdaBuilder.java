/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.resource.Resource;
import ai.asserts.aws.resource.ResourceRelation;
import io.micrometer.core.instrument.util.StringUtils;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetMethodRequest;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.apigateway.model.GetResourcesRequest;
import software.amazon.awssdk.services.apigateway.model.GetResourcesResponse;
import software.amazon.awssdk.services.apigateway.model.GetRestApisResponse;
import software.amazon.awssdk.services.apigateway.model.RestApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ACCOUNT_ID_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_REGION_LABEL;
import static ai.asserts.aws.resource.ResourceType.ApiGateway;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;

@Component
@Slf4j
public class ApiGatewayToLambdaBuilder extends Collector
        implements MetricProvider, InitializingBean {
    private final AWSClientProvider awsClientProvider;
    private final RateLimiter rateLimiter;
    private final AccountProvider accountProvider;
    private final MetricSampleBuilder metricSampleBuilder;
    private final CollectorRegistry collectorRegistry;
    private final Pattern LAMBDA_URI_PATTERN = Pattern.compile(
            "arn:aws:apigateway:(.+?):lambda:path/.+?/functions/arn:aws:lambda:(.+?):(.+?):function:(.+)/invocations");

    @Getter
    private volatile Set<ResourceRelation> lambdaIntegrations = new HashSet<>();
    private volatile List<MetricFamilySamples> apiResourceMetrics = new ArrayList<>();

    public ApiGatewayToLambdaBuilder(AWSClientProvider awsClientProvider,
                                     RateLimiter rateLimiter, AccountProvider accountProvider,
                                     MetricSampleBuilder metricSampleBuilder,
                                     CollectorRegistry collectorRegistry) {
        this.awsClientProvider = awsClientProvider;
        this.rateLimiter = rateLimiter;
        this.accountProvider = accountProvider;
        this.metricSampleBuilder = metricSampleBuilder;
        this.collectorRegistry = collectorRegistry;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        collectorRegistry.register(this);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return apiResourceMetrics;
    }

    public void update() {
        log.info("Exporting ApiGateway to Lambda relationship");
        Set<ResourceRelation> newIntegrations = new HashSet<>();
        List<MetricFamilySamples> newMetrics = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        try {
            for (AWSAccount accountRegion : accountProvider.getAccounts()) {
                accountRegion.getRegions().forEach(region -> {
                    try {
                        ApiGatewayClient client = awsClientProvider.getApiGatewayClient(region, accountRegion);
                        SortedMap<String, String> labels = new TreeMap<>();
                        String getRestApis = "ApiGatewayClient/getRestApis";
                        labels.put(SCRAPE_OPERATION_LABEL, getRestApis);
                        labels.put(SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId());
                        labels.put(SCRAPE_REGION_LABEL, region);
                        GetRestApisResponse restApis = rateLimiter.doWithRateLimit(getRestApis, labels, client::getRestApis);
                        if (restApis.hasItems()) {
                            restApis.items().forEach(restApi -> {
                                String getResources = "getResources";
                                labels.put(SCRAPE_OPERATION_LABEL, getResources);
                                GetResourcesResponse resources = rateLimiter.doWithRateLimit(getResources, labels,
                                        () -> client.getResources(GetResourcesRequest.builder()
                                                .restApiId(restApi.id())
                                                .build()));
                                if (resources.hasItems()) {
                                    resources.items().forEach(resource -> {
                                        captureIntegrations(client, newIntegrations, accountRegion.getAccountId(),
                                                labels, region, restApi, resource);
                                        Map<String, String> apiResourceLabels = new TreeMap<>();
                                        apiResourceLabels.put(SCRAPE_ACCOUNT_ID_LABEL, accountRegion.getAccountId());
                                        apiResourceLabels.put(SCRAPE_REGION_LABEL, region);
                                        apiResourceLabels.put("aws_resource_type", "AWS::ApiGateway::RestApi");
                                        apiResourceLabels.put("namespace", "AWS/ApiGateway");
                                        apiResourceLabels.put("name", restApi.name());
                                        apiResourceLabels.put("id", restApi.id());
                                        apiResourceLabels.put("job", restApi.name());
                                        samples.add(metricSampleBuilder.buildSingleSample("aws_resource",
                                                apiResourceLabels, 1.0d));
                                    });
                                }
                            });
                        }
                    } catch (Exception e) {
                        log.error("Failed to discover lambda integrations for " + accountRegion, e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to discover lambda integrations", e);
        }

        if (samples.size() > 0) {
            newMetrics.add(metricSampleBuilder.buildFamily(samples));
        }

        lambdaIntegrations = newIntegrations;
        apiResourceMetrics = newMetrics;
    }

    private void captureIntegrations(ApiGatewayClient client, Set<ResourceRelation> newIntegrations, String accountId,
                                     SortedMap<String, String> labels, String region, RestApi restApi,
                                     software.amazon.awssdk.services.apigateway.model.Resource apiResource) {
        if (apiResource.hasResourceMethods()) {
            apiResource.resourceMethods().forEach((name, method) -> {
                String api = "ApiGatewayClient/getMethod";
                labels.put(SCRAPE_OPERATION_LABEL, api);
                GetMethodRequest req = GetMethodRequest.builder()
                        .restApiId(restApi.id())
                        .resourceId(apiResource.id())
                        .httpMethod(name)
                        .build();
                GetMethodResponse resp = rateLimiter.doWithRateLimit(api, labels, () -> client.getMethod(req));
                String uri = resp.methodIntegration().uri();
                if (StringUtils.isEmpty(uri)) {
                    return;
                }
                Matcher matcher = LAMBDA_URI_PATTERN.matcher(uri);
                if (matcher.matches()) {
                    ResourceRelation resourceRelation = ResourceRelation.builder()
                            .from(Resource.builder()
                                    .type(ApiGateway)
                                    .name(restApi.name())
                                    .id(restApi.id())
                                    .region(region)
                                    .account(accountId)
                                    .build())
                            .to(Resource.builder()
                                    .type(LambdaFunction)
                                    .name(matcher.group(4))
                                    .region(matcher.group(2))
                                    .account(matcher.group(3))
                                    .build())
                            .name("FORWARDS_TO")
                            .build();
                    newIntegrations.add(resourceRelation);
                }
            });
        }
    }
}
