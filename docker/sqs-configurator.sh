#!/bin/sh
# Creates the queue topology the application expects to already exist. In a real
# environment this is infrastructure-as-code; here it is the compose equivalent, and
# the point is the same: the application never creates its own queues, so a typo in a
# queue name fails loudly instead of silently producing an empty queue nobody reads.
set -eu

ENDPOINT="${LOCALSTACK_ENDPOINT:-http://localstack:4566}"
QUEUE="${QUEUE_NAME:-conta-bancaria-criada}"
DLQ="${QUEUE}-dlq"

sqs() {
    aws --endpoint-url "$ENDPOINT" sqs "$@"
}

# Retention on the dead-letter queue is longer than on the source queue: a message
# only reaches it after the source already spent its own retention budget, and
# whoever investigates it needs time the source queue no longer has.
sqs create-queue --queue-name "$DLQ" --attributes MessageRetentionPeriod=1209600 >/dev/null

dlq_url=$(sqs get-queue-url --queue-name "$DLQ" --query QueueUrl --output text)
dlq_arn=$(sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn \
    --query Attributes.QueueArn --output text)

# maxReceiveCount of 5 is a budget, not a retry count: the receive count never resets,
# so it has to absorb a short database outage without dead-lettering valid messages,
# while still retiring a poison message quickly.
cat > /tmp/queue-attributes.json <<EOF
{
  "VisibilityTimeout": "30",
  "MessageRetentionPeriod": "345600",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"${dlq_arn}\",\"maxReceiveCount\":\"5\"}"
}
EOF

sqs create-queue --queue-name "$QUEUE" --attributes file:///tmp/queue-attributes.json >/dev/null

echo "queue ${QUEUE} ready with dead-letter queue ${DLQ}"
