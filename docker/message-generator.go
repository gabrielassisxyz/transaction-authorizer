package main

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"net/url"
	"time"

	"os"
	"strconv"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/aws/smithy-go"
	smithyendpoints "github.com/aws/smithy-go/endpoints"
	"github.com/google/uuid"
)

// --- ACCOUNT PARAMETERS ---
var (
	accountCount = getEnvInt("TOTAL_ACCOUNTS", 1000)
)

// --- AWS PARAMETERS ---
const (
	awsRegion       = "sa-east-1"
	awsAccessKey    = "test"
	awsSecretKey    = "test"
	awsSessionToken = "test"
)

// --- SQS PARAMETERS ---
var (
	queueName          = getEnvStr("QUEUE_NAME", "conta-bancaria-criada")
	localstackEndpoint = getEnvStr("LOCALSTACK_ENDPOINT", "http://localhost:4566")
	batchSize          = 10
	contentTypeKey     = "ContentType"
	contentTypeVal     = "application/json"
)

func getEnvStr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return fallback
}

func setupSQS(ctx context.Context) (*sqs.Client, string) {
	cfg, err := config.LoadDefaultConfig(ctx,
		config.WithRegion(awsRegion),
		config.WithCredentialsProvider(aws.NewCredentialsCache(
			credentials.NewStaticCredentialsProvider(awsAccessKey, awsSecretKey, awsSessionToken))),
	)
	if err != nil {
		log.Fatalf("[ERROR] Failed to load AWS config: %v", err)
	}

	sqsClient := sqs.NewFromConfig(cfg, func(o *sqs.Options) {
		o.EndpointResolverV2 = &customSQSEndpointResolver{}
	})

	getQueueUrlOut, err := sqsClient.GetQueueUrl(ctx, &sqs.GetQueueUrlInput{
		QueueName: aws.String(queueName),
	})
	if err == nil {
		return sqsClient, *getQueueUrlOut.QueueUrl
	}

	log.Printf("[WARN] Queue not found, creating: %s", queueName)
	createOut, createErr := sqsClient.CreateQueue(ctx, &sqs.CreateQueueInput{
		QueueName: aws.String(queueName),
	})
	if createErr != nil {
		log.Fatalf("[ERROR] Failed to create SQS queue: %v", createErr)
	}
	return sqsClient, *createOut.QueueUrl
}

type AccountInfo struct {
	ID        string
	OwnerID   string
	CreatedAt int64
	Status    string
}

func generateAccounts() ([]AccountInfo, map[string]float64) {
	now := time.Now().Unix()
	fiveYearsAgo := now - 5*365*24*60*60
	accounts := make([]AccountInfo, accountCount)
	balances := make(map[string]float64, accountCount)
	for i := range accounts {
		id := uuid.New().String()
		accounts[i] = AccountInfo{
			ID:        id,
			OwnerID:   uuid.New().String(),
			CreatedAt: rand.Int63n(now-fiveYearsAgo+1) + fiveYearsAgo,
			Status:    "ENABLED",
		}
		balances[id] = 0
	}
	return accounts, balances
}

// Instead of transactions, we now just send account objects
func generateAccountMessages(accounts []AccountInfo) []AccountInfo {
	return accounts
}

func sendAllAccountBatches(ctx context.Context, sqsClient *sqs.Client, queueUrl string, accounts []AccountInfo) int {
	sent := 0
	for i := 0; i < len(accounts); i += batchSize {
		end := min(i+batchSize, len(accounts))
		batch := accounts[i:end]
		if err := sendAccountBatch(ctx, sqsClient, queueUrl, batch); err != nil {
			log.Fatalf("[ERROR] Failed to send account batch to SQS: %v", err)
		}
		sent += len(batch)
	}
	return sent
}

func printQueueStats(ctx context.Context, sqsClient *sqs.Client, queueUrl string) {
	attrsOut, err := sqsClient.GetQueueAttributes(ctx, &sqs.GetQueueAttributesInput{
		QueueUrl:       aws.String(queueUrl),
		AttributeNames: []types.QueueAttributeName{"All"},
	})
	if err != nil {
		log.Printf("[WARN] Could not get queue attributes: %v", err)
		return
	}
	log.Printf("[SQS Stats] Attributes for queue '%s':", queueName)
	for k, v := range attrsOut.Attributes {
		log.Printf("  %s: %s", k, v)
	}
}

func sendAccountBatch(ctx context.Context, client *sqs.Client, queueUrl string, accounts []AccountInfo) error {
	entries := make([]types.SendMessageBatchRequestEntry, len(accounts))
	for i, acc := range accounts {
		body := fmt.Sprintf(
			`{"account":{"id":"%s","owner":"%s","created_at":"%d","status":"%s"}}`,
			acc.ID, acc.OwnerID, acc.CreatedAt, acc.Status,
		)
		entries[i] = types.SendMessageBatchRequestEntry{
			Id:          aws.String(fmt.Sprintf("msg-%d", i)),
			MessageBody: aws.String(body),
			MessageAttributes: map[string]types.MessageAttributeValue{
				contentTypeKey: {
					DataType:    aws.String("String"),
					StringValue: aws.String(contentTypeVal),
				},
			},
		}
	}
	_, err := client.SendMessageBatch(ctx, &sqs.SendMessageBatchInput{
		QueueUrl: aws.String(queueUrl),
		Entries:  entries,
	})
	return err
}

type customSQSEndpointResolver struct{}

func (r *customSQSEndpointResolver) ResolveEndpoint(ctx context.Context, params sqs.EndpointParameters) (smithyendpoints.Endpoint, error) {
	u, err := url.Parse(localstackEndpoint)
	if err != nil {
		return smithyendpoints.Endpoint{}, err
	}
	var props smithy.Properties
	props.Set("authSigningRegion", awsRegion)
	return smithyendpoints.Endpoint{
		URI:        *u,
		Properties: props,
	}, nil
}

func main() {
	start := time.Now()
	ctx := context.Background()

	sqsClient, queueUrl := setupSQS(ctx)

	accounts, _ := generateAccounts()
	accountMsgs := generateAccountMessages(accounts)

	log.Printf("[INFO] Sending %d account messages to SQS queue '%s'", len(accountMsgs), queueName)
	sent := sendAllAccountBatches(ctx, sqsClient, queueUrl, accountMsgs)

	log.Printf("[INFO] Done. Sent %d account messages to SQS queue '%s'", sent, queueName)
	log.Printf("[INFO] Total execution time: %d ms", time.Since(start).Milliseconds())

	printQueueStats(ctx, sqsClient, queueUrl)
}
