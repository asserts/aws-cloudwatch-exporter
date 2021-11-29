/*
 *  Copyright © 2020.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.exporter;

import ai.asserts.aws.cloudwatch.query.MetricQuery;
import com.google.common.collect.ImmutableSortedMap;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class BasicMetricCollector extends Collector {
    private final Map<Key, Double> gaugeValues = new ConcurrentHashMap<>();
    private final Map<Key, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<Key, LatencyCounter> latencyCounters = new ConcurrentHashMap<>();

    public void exportMetricMeta(String region, MetricQuery query) {
        Integer scrapeInterval = query.getMetricConfig().getNamespace().getScrapeInterval();
        recordGaugeValue("cw_scrape_interval_seconds", ImmutableSortedMap.of(
                "region", region,
                "namespace", query.getMetricConfig().getNamespace().getName()
        ), 1.0D * scrapeInterval);
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> familySamples = new ArrayList<>();

        Map<String, List<Sample>> gaugeSamples = new TreeMap<>();
        gaugeValues.forEach((key, value) ->
                gaugeSamples.computeIfAbsent(key.metricName, k -> new ArrayList<>())
                        .add(new Sample(key.metricName, key.labelNames, key.labelValues, value)));
        gaugeSamples.forEach((name, samples) ->
                familySamples.add(new MetricFamilySamples(name, Type.GAUGE, "", samples)));

        Map<String, List<Sample>> counterSamples = new TreeMap<>();
        counters.forEach((key, value) ->
                counterSamples.computeIfAbsent(key.metricName, k -> new ArrayList<>())
                        .add(new Sample(key.metricName, key.labelNames, key.labelValues, value.get())));
        counterSamples.forEach((name, samples) ->
                familySamples.add(new MetricFamilySamples(name, Type.COUNTER, "", samples)));

        Map<String, List<Sample>> latencySamples = new TreeMap<>();
        latencyCounters.forEach((key, latencyCounter) -> {
            int count = latencyCounter.getCount();
            double value = latencyCounter.getValue();
            latencySamples.computeIfAbsent(key.metricName + "_count", k -> new ArrayList<>())
                    .add(new Sample(key.metricName + "_count", key.labelNames, key.labelValues, count));
            latencySamples.computeIfAbsent(key.metricName + "_sum", k -> new ArrayList<>())
                    .add(new Sample(key.metricName + "_sum", key.labelNames, key.labelValues, value));
        });

        latencySamples.forEach((name, samples) ->
                familySamples.add(new MetricFamilySamples(name, Type.COUNTER, "", samples)));

        return familySamples;
    }

    public void recordGaugeValue(String metricName, SortedMap<String, String> labels, Double value) {
        gaugeValues.put(Key.builder()
                .metricName(metricName)
                .labelNames(new ArrayList<>(labels.keySet()))
                .labelValues(new ArrayList<>(labels.values()))
                .build(), value);
    }

    public void recordCounterValue(String metricName, SortedMap<String, String> labels, int value) {
        Key key = Key.builder()
                .metricName(metricName)
                .labelNames(new ArrayList<>(labels.keySet()))
                .labelValues(new ArrayList<>(labels.values()))
                .build();
        counters.computeIfAbsent(key, k -> {
            log.info("Creating counter {}{}", key.metricName, labels);
            return new AtomicLong();
        }).addAndGet(value);
    }

    public void recordLatency(String metricName, SortedMap<String, String> labels, long value) {
        Key key = Key.builder()
                .metricName(metricName)
                .labelNames(new ArrayList<>(labels.keySet()))
                .labelValues(new ArrayList<>(labels.values()))
                .build();
        latencyCounters.computeIfAbsent(key, k -> {
            log.info("Creating counter {}{}", key.metricName + "_count", labels);
            log.info("Creating counter {}{}", key.metricName + "_sum", labels);
            return new LatencyCounter();
        }).increment(value);
    }

    @Builder
    @EqualsAndHashCode
    @ToString
    @Getter
    public static class Key {
        private final String metricName;
        private final List<String> labelNames;
        private final List<String> labelValues;
    }

    public static class LatencyCounter {
        private final AtomicLong valueTotal = new AtomicLong(0);
        private final AtomicInteger count = new AtomicInteger(0);

        public void increment(Long value) {
            valueTotal.addAndGet(value);
            count.incrementAndGet();
        }

        public int getCount() {
            return count.intValue();
        }

        public double getValue() {
            return valueTotal.doubleValue();
        }
    }

}
