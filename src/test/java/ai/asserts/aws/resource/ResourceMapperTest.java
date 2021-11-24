
package ai.asserts.aws.resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ai.asserts.aws.resource.ResourceType.DynamoDBTable;
import static ai.asserts.aws.resource.ResourceType.ECSCluster;
import static ai.asserts.aws.resource.ResourceType.ECSService;
import static ai.asserts.aws.resource.ResourceType.ECSTask;
import static ai.asserts.aws.resource.ResourceType.ECSTaskDef;
import static ai.asserts.aws.resource.ResourceType.EventBus;
import static ai.asserts.aws.resource.ResourceType.LambdaFunction;
import static ai.asserts.aws.resource.ResourceType.S3Bucket;
import static ai.asserts.aws.resource.ResourceType.SNSTopic;
import static ai.asserts.aws.resource.ResourceType.SQSQueue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceMapperTest {
    private ResourceMapper testClass;

    @BeforeEach
    public void setup() {
        testClass = new ResourceMapper();
    }

    @Test
    public void map_SQSQueue() {
        String arn = "arn:aws:sqs:us-west-2:342994379019:lamda-sqs-poc-input-queue";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(SQSQueue).arn(arn)
                        .region("us-west-2")
                        .name("lamda-sqs-poc-input-queue")
                        .build()),
                testClass.map(arn)
        );
    }

    @Test
    public void map_DynamoDBTable() {
        String arn = "arn:aws:dynamodb:us-west-2:342994379019:table/auction_app_bids/stream/2021-06-01T05:03:12.707";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(DynamoDBTable)
                        .arn(arn)
                        .region("us-west-2")
                        .name("auction_app_bids")
                        .build()),
                testClass.map(arn)
        );
    }

    @Test
    public void map_LambdaFunction() {
        String arn = "arn:aws:lambda:us-west-2:342994379019:function:lambda-poc-dynamodb-updates";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(LambdaFunction)
                        .region("us-west-2")
                        .arn(arn).name("lambda-poc-dynamodb-updates")
                        .build()),
                testClass.map(arn)
        );

        arn = "arn:aws:lambda:us-west-2:342994379019:function:lambda-poc-dynamodb-updates:version1";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(LambdaFunction)
                        .region("us-west-2")
                        .arn(arn).name("lambda-poc-dynamodb-updates")
                        .build()),
                testClass.map(arn)
        );
    }

    @Test
    public void map_S3Bucket() {
        String arn = "arn:aws:s3:::ai-asserts-dev-custom-rules";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(S3Bucket).arn(arn)
                        .region("")
                        .name("ai-asserts-dev-custom-rules")
                        .build()),
                testClass.map(arn)
        );

        arn = "arn:aws:s3:us-west-2:342994379019:ai-asserts-dev-custom-rules";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(S3Bucket)
                        .region("us-west-2")
                        .arn(arn).name("ai-asserts-dev-custom-rules")
                        .build()),
                testClass.map(arn)
        );
    }

    @Test
    public void map_SNS_Topic() {
        String arn = "arn:aws:sns:us-west-2:342994379019:topic-name";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(SNSTopic).arn(arn)
                        .region("us-west-2")
                        .name("topic-name")
                        .build()),
                testClass.map(arn)
        );
    }

    @Test
    public void map_EventBus_Topic() {
        String arn = "arn:aws:events:us-west-2:342994379019:event-bus/event-bus-name";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(EventBus).arn(arn)
                        .region("us-west-2")
                        .name("event-bus-name")
                        .build()),
                testClass.map(arn)
        );
    }

    @Test
    public void map_ECS_Cluster() {
        String arn = "arn:aws:ecs:us-west-2:342994379019:cluster/cluster1";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(ECSCluster).arn(arn)
                        .region("us-west-2")
                        .name("cluster1")
                        .build()),
                testClass.map(arn)
        );
    }

    @Test
    public void map_ECS_Service() {
        String arn = "arn:aws:ecs:us-west-2:342994379019:service/ecs-cluster/service1";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(ECSService).arn(arn)
                        .region("us-west-2")
                        .name("service1")
                        .childOf(Resource.builder().type(ECSCluster).name("ecs-cluster").region("us-west-2").build())
                        .build()),
                testClass.map(arn)
        );
    }

    @Test
    public void map_ECS_TaskDefinition() {
        String arn = "arn:aws:ecs:us-west-2:342994379019:task-definition/item-service-v2:5";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(ECSTaskDef).arn(arn)
                        .region("us-west-2")
                        .name("item-service-v2")
                        .version("5")
                        .build()),
                testClass.map(arn)
        );
    }

    @Test
    public void map_ECS_Task() {
        String arn = "arn:aws:ecs:us-west-2:342994379019:task/ecs-sample-app/34c11488dc56429fb67e2996b5ceaa74";
        assertEquals(
                Optional.of(Resource.builder()
                        .type(ECSTask).arn(arn)
                        .region("us-west-2")
                        .name("34c11488dc56429fb67e2996b5ceaa74")
                        .build()),
                testClass.map(arn)
        );
    }
}
