package ai.asserts.aws.config;


import ai.asserts.aws.model.CWNamespace;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("FieldMayBeFinal")
@EqualsAndHashCode
@ToString
public class ScrapeConfig {
    @Setter
    @Builder.Default
    private Set<String> regions = new TreeSet<>();

    @Builder.Default
    private List<NamespaceConfig> namespaces = new ArrayList<>();

    @Builder.Default
    private Integer scrapeInterval = 60;

    @Builder.Default
    private Integer delay = 0;

    @Builder.Default
    private Integer listMetricsResultCacheTTLMinutes = 10;

    @Builder.Default
    private Integer listFunctionsResultCacheTTLMinutes = 5;

    @Builder.Default
    private Integer getResourcesResultCacheTTLMinutes = 5;

    @Builder.Default
    private Integer numTaskThreads = 5;

    @Builder.Default
    private AuthConfig authConfig = new AuthConfig();

    @Builder.Default
    private String ecsTargetSDFile = "/opt/asserts/ecs-scrape-targets.yml";

    @Builder.Default
    private Integer logScrapeDelaySeconds = 15;

    @Builder.Default
    @Setter
    private boolean discoverECSTasks = false;

    @Builder.Default
    @Setter
    private boolean discoverAllECSTasksByDefault = true;

    @Builder.Default
    private Set<String> discoverResourceTypes = new TreeSet<>();

    @Builder.Default
    private List<ECSTaskDefScrapeConfig> ecsTaskScrapeConfigs = new ArrayList<>();

    private TagExportConfig tagExportConfig;

    private String alertForwardUrl;

    private String tenant;

    private String assumeRole;

    @Builder.Default
    private List<RelabelConfig> relabelConfigs = new ArrayList<>();

    @Builder.Default
    private List<DimensionToLabel> dimensionToLabels = new ArrayList<>();

    @Builder.Default
    private boolean pullCWAlarms = true;

    @Builder.Default
    private boolean cwAlarmAsMetric = true;

    @Builder.Default
    private boolean logVerbose = false;

    public Optional<NamespaceConfig> getLambdaConfig() {
        if (CollectionUtils.isEmpty(namespaces)) {
            return Optional.empty();
        }
        return namespaces.stream()
                .filter(namespaceConfig -> CWNamespace.lambda.isThisNamespace(namespaceConfig.getName()))
                .findFirst();
    }

    public Set<String> getRegions() {
        return regions;
    }

    public boolean isDiscoverECSTasks() {
        return discoverECSTasks;
    }

    public boolean shouldExportTag(String tagName, String tagValue) {
        if (tagExportConfig != null) {
            return tagExportConfig.shouldCaptureTag(tagName, tagValue);
        }
        return false;
    }

    public Map<String, String> getEntityLabels(String namespace, Map<String, String> alarmDimensions) {
        boolean unknownNamespace = !dimensionToLabels.stream()
                .map(DimensionToLabel::getNamespace)
                .collect(Collectors.toSet())
                .contains(namespace);

        boolean knownDimension = dimensionToLabels.stream()
                .map(DimensionToLabel::getDimensionName)
                .collect(Collectors.toSet())
                .stream().anyMatch(alarmDimensions::containsKey);

        SortedMap<String, String> labels = new TreeMap<>();
        dimensionToLabels.stream()
                .filter(dimensionToLabel -> captureDimension(namespace, alarmDimensions, dimensionToLabel))
                .forEach(dimensionToLabel -> mapTypeAndName(alarmDimensions, labels, dimensionToLabel));


        if (unknownNamespace && knownDimension) {
            dimensionToLabels.stream()
                    .filter(d -> alarmDimensions.containsKey(d.getDimensionName()))
                    .findFirst().ifPresent(dimensionToLabel -> {
                        mapTypeAndName(alarmDimensions, labels, dimensionToLabel);
                        labels.put("namespace", dimensionToLabel.getNamespace());
                    });
        }

        return labels;
    }

    private void mapTypeAndName(Map<String, String> alarmDimensions, SortedMap<String, String> labels, DimensionToLabel dimensionToLabel) {
        String toLabel = dimensionToLabel.getMapToLabel();
        String dimensionName = dimensionToLabel.getDimensionName();
        labels.put(toLabel, alarmDimensions.get(dimensionName));
        if (StringUtils.hasLength(dimensionToLabel.getEntityType())) {
            labels.put("asserts_entity_type", dimensionToLabel.getEntityType());
        }
    }

    private boolean captureDimension(String namespace, Map<String, String> alarmDimensions,
                                     DimensionToLabel dimensionToLabel) {
        return (dimensionToLabel.getNamespace().equals(namespace)) &&
                alarmDimensions.containsKey(dimensionToLabel.getDimensionName());
    }

    @VisibleForTesting
    public void validateConfig() {
        if (!CollectionUtils.isEmpty(getNamespaces())) {
            for (int i = 0; i < getNamespaces().size(); i++) {
                NamespaceConfig namespaceConfig = getNamespaces().get(i);
                namespaceConfig.setScrapeConfig(this);
                namespaceConfig.validate(i);
            }
        }

        if (!CollectionUtils.isEmpty(getEcsTaskScrapeConfigs())) {
            getEcsTaskScrapeConfigs().forEach(ECSTaskDefScrapeConfig::validate);
        }

        if (getTagExportConfig() != null) {
            getTagExportConfig().compile();
        }
        relabelConfigs.forEach(RelabelConfig::validate);
        authConfig.validate();
    }

    public Map<String, String> additionalLabels(String metricName, Map<String, String> inputLabels) {
        Map<String, String> labels = new TreeMap<>(inputLabels);
        for (RelabelConfig config : relabelConfigs) {
            labels = config.addReplacements(metricName, labels);
        }
        return labels;
    }

    @JsonIgnore
    public Map<String, Map<Integer, ECSTaskDefScrapeConfig>> getECSConfigByNameAndPort() {
        Map<String, Map<Integer, ECSTaskDefScrapeConfig>> configs = new TreeMap<>();
        ecsTaskScrapeConfigs.forEach(c -> {
            String containerName = c.getContainerDefinitionName();
            Map<Integer, ECSTaskDefScrapeConfig> map = configs.computeIfAbsent(containerName, k -> new TreeMap<>());
            if (c.getContainerPort() != null) {
                map.put(c.getContainerPort(), c);
            } else {
                map.put(-1, c);
            }
        });
        return configs;
    }
}


