/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.resource.ResourceRelation;
import io.prometheus.client.Collector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

@Component
@Slf4j
public class ResourceRelationExporter extends Collector implements MetricProvider {
    private final ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter;
    private final LBToASGRelationBuilder lbToASGRelationBuilder;
    private final LBToLambdaRoutingBuilder lbToLambdaRoutingBuilder;
    private final EC2ToEBSVolumeExporter ec2ToEBSVolumeExporter;
    private final ApiGatewayToLambdaBuilder apiGatewayToLambdaBuilder;
    private final MetricSampleBuilder sampleBuilder;
    private volatile List<MetricFamilySamples> metrics = new ArrayList<>();

    public ResourceRelationExporter(ECSServiceDiscoveryExporter ecsServiceDiscoveryExporter,
                                    LBToASGRelationBuilder lbToASGRelationBuilder,
                                    LBToLambdaRoutingBuilder lbToLambdaRoutingBuilder,
                                    EC2ToEBSVolumeExporter ec2ToEBSVolumeExporter,
                                    ApiGatewayToLambdaBuilder apiGatewayToLambdaBuilder,
                                    MetricSampleBuilder sampleBuilder) {
        this.ecsServiceDiscoveryExporter = ecsServiceDiscoveryExporter;
        this.lbToASGRelationBuilder = lbToASGRelationBuilder;
        this.lbToLambdaRoutingBuilder = lbToLambdaRoutingBuilder;
        this.ec2ToEBSVolumeExporter = ec2ToEBSVolumeExporter;
        this.apiGatewayToLambdaBuilder = apiGatewayToLambdaBuilder;
        this.sampleBuilder = sampleBuilder;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        return metrics;
    }

    @Override
    public void update() {
        log.info("Export resource relationships");
        try {
            List<MetricFamilySamples> familySamples = new ArrayList<>();
            List<MetricFamilySamples.Sample> samples = new ArrayList<>();
            Set<ResourceRelation> relations = new HashSet<>(ecsServiceDiscoveryExporter.getRouting());
            relations.addAll(lbToASGRelationBuilder.getRoutingConfigs());
            relations.addAll(lbToLambdaRoutingBuilder.getRoutings());
            relations.addAll(ec2ToEBSVolumeExporter.getAttachedVolumes());
            relations.addAll(apiGatewayToLambdaBuilder.getLambdaIntegrations());

            log.info("Found {} resource relations ", relations.size());
            relations.forEach(relation -> {
                String name = "aws_resource_relation";
                SortedMap<String, String> labels = new TreeMap<>();
                relation.getFrom().addLabels(labels, "from");
                relation.getTo().addLabels(labels, "to");
                labels.put("rel_name", relation.getName());
                samples.add(sampleBuilder.buildSingleSample(name, labels, 1.0D));
            });
            if (samples.size() > 0) {
                familySamples.add(sampleBuilder.buildFamily(samples));
            }
            log.info("Emitted {} metrics ", samples.size());
            metrics = familySamples;
        } catch (Exception e) {
            log.error("Failed to build metrics", e);
        }
    }
}
