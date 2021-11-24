
package ai.asserts.aws.resource;

public enum ResourceType {
    ECSCluster,
    ECSService,
    ECSTaskDef,
    ECSTask,
    SNSTopic,
    EventBus,
    SQSQueue, // SQS Queue
    DynamoDBTable, // Dynamo DB Table
    LambdaFunction, // Lambda function
    S3Bucket  // S3 Bucket
}
