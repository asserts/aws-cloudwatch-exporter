/*
 *  Copyright © 2021.
 *  Asserts, Inc. - All Rights Reserved
 */
package ai.asserts.aws.cloudwatch.alarms;

import ai.asserts.aws.AWSClientProvider;
import ai.asserts.aws.AccountProvider;
import ai.asserts.aws.AccountProvider.AWSAccount;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.ScrapeConfigProvider;
import ai.asserts.aws.config.ScrapeConfig;
import ai.asserts.aws.exporter.AccountIDProvider;
import ai.asserts.aws.exporter.MetricSampleBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;

import java.time.Instant;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlarmFetcherTest extends EasyMockSupport {
    public CollectorRegistry collectorRegistry;
    private AccountProvider accountProvider;
    private AWSAccount awsAccount;
    private RateLimiter rateLimiter;
    private AWSClientProvider awsClientProvider;
    private ScrapeConfigProvider scrapeConfigProvider;
    private ScrapeConfig scrapeConfig;
    private CloudWatchClient cloudWatchClient;
    private AccountIDProvider accountIDProvider;
    private AlarmMetricConverter alarmMetricConverter;
    private MetricSampleBuilder sampleBuilder;
    private AlertsProcessor alertsProcessor;
    private AlarmFetcher testClass;
    private Collector.MetricFamilySamples.Sample sample;
    private Collector.MetricFamilySamples familySamples;
    private Instant now;

    @BeforeEach
    public void setup() {
        now = Instant.now();
        awsAccount = new AWSAccount("123456789", "", "", "", ImmutableSet.of("region"));
        accountProvider = mock(AccountProvider.class);
        scrapeConfigProvider = mock(ScrapeConfigProvider.class);
        scrapeConfig = mock(ScrapeConfig.class);
        awsClientProvider = mock(AWSClientProvider.class);
        rateLimiter = mock(RateLimiter.class);
        sampleBuilder = mock(MetricSampleBuilder.class);
        collectorRegistry = mock(CollectorRegistry.class);
        cloudWatchClient = mock(CloudWatchClient.class);
        accountIDProvider = mock(AccountIDProvider.class);
        alarmMetricConverter = mock(AlarmMetricConverter.class);
        sample = mock(Collector.MetricFamilySamples.Sample.class);
        familySamples = mock(Collector.MetricFamilySamples.class);
        alertsProcessor = mock(AlertsProcessor.class);
        testClass = new AlarmFetcher(accountProvider, awsClientProvider, collectorRegistry, rateLimiter,
                sampleBuilder, alarmMetricConverter, scrapeConfigProvider, alertsProcessor);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sendAlarmsForRegions_exposeAsMetric() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.isPullCWAlarms()).andReturn(true);
        expect(scrapeConfig.getAlertForwardUrl()).andReturn(null).anyTimes();
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount));
        expect(accountIDProvider.getAccountId()).andReturn("123456789").anyTimes();
        expect(awsClientProvider.getCloudWatchClient("region", awsAccount))
                .andReturn(cloudWatchClient).anyTimes();

        Capture<RateLimiter.AWSAPICall<DescribeAlarmsResponse>> callbackCapture = Capture.newInstance();

        MetricAlarm alarm = MetricAlarm.builder()
                .alarmName("alarm1")
                .stateValue("ALARM")
                .stateUpdatedTimestamp(now)
                .threshold(10.0)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .namespace("AWS/RDS")
                .build();
        DescribeAlarmsResponse response = DescribeAlarmsResponse.builder()
                .metricAlarms(ImmutableList.of(alarm))
                .build();

        expect(alarmMetricConverter.extractMetricAndEntityLabels(alarm))
                .andReturn(ImmutableMap.of("label1", "value1"));

        expect(rateLimiter.doWithRateLimit(eq("CloudWatchClient/describeAlarms"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        SortedMap<String, String> labels = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("account_id", "123456789")
                .put("label1", "value1")
                .put("namespace", "AWS/RDS")
                .put("metric_namespace", "AWS/RDS")
                .put("metric_operator", ">")
                .put("region", "region")
                .put("state", "ALARM")
                .put("threshold", "10.0")
                .put("timestamp", now.toString())
                .build());
        alarmMetricConverter.simplifyAlarmName(labels);
        expect(sampleBuilder.buildSingleSample("aws_cloudwatch_alarm", labels, 1.0D))
                .andReturn(sample);
        expect(sampleBuilder.buildFamily(ImmutableList.of(sample))).andReturn(familySamples);
        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(familySamples), testClass.collect());

        verifyAll();
    }

    @Test
    public void sendAlarmsForRegions_forwardAlerts() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig).anyTimes();
        expect(scrapeConfig.isPullCWAlarms()).andReturn(true);
        expect(scrapeConfig.getAlertForwardUrl()).andReturn("url").anyTimes();
        expect(accountProvider.getAccounts()).andReturn(ImmutableSet.of(awsAccount));
        expect(accountIDProvider.getAccountId()).andReturn("123456789").anyTimes();
        expect(awsClientProvider.getCloudWatchClient("region", awsAccount))
                .andReturn(cloudWatchClient).anyTimes();

        Capture<RateLimiter.AWSAPICall<DescribeAlarmsResponse>> callbackCapture = Capture.newInstance();

        MetricAlarm alarm = MetricAlarm.builder()
                .alarmName("alarm1")
                .stateValue("ALARM")
                .stateUpdatedTimestamp(now)
                .threshold(10.0)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .namespace("AWS/RDS")
                .build();
        DescribeAlarmsResponse response = DescribeAlarmsResponse.builder()
                .metricAlarms(ImmutableList.of(alarm))
                .build();

        expect(alarmMetricConverter.extractMetricAndEntityLabels(alarm))
                .andReturn(ImmutableMap.of("label1", "value1"));

        expect(rateLimiter.doWithRateLimit(eq("CloudWatchClient/describeAlarms"),
                anyObject(SortedMap.class), capture(callbackCapture))).andReturn(response);
        SortedMap<String, String> labels = new TreeMap<>(new ImmutableMap.Builder<String, String>()
                .put("account_id", "123456789")
                .put("label1", "value1")
                .put("namespace", "AWS/RDS")
                .put("metric_namespace", "AWS/RDS")
                .put("metric_operator", ">")
                .put("region", "region")
                .put("state", "ALARM")
                .put("threshold", "10.0")
                .put("timestamp", now.toString())
                .build());
        alarmMetricConverter.simplifyAlarmName(labels);
        alertsProcessor.sendAlerts(ImmutableList.of(labels));
        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(), testClass.collect());

        verifyAll();
    }

    @Test
    public void pullAlarm_disabled() {
        expect(scrapeConfigProvider.getScrapeConfig()).andReturn(scrapeConfig);
        expect(scrapeConfig.isPullCWAlarms()).andReturn(false);
        replayAll();
        testClass.update();
        assertEquals(ImmutableList.of(), testClass.collect());

        verifyAll();
    }
}
