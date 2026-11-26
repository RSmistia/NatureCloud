#!/bin/bash

source config.sh

INSTANCE_IDS=$(aws ec2 describe-instances \
  --region "$AWS_DEFAULT_REGION" \
  --query 'Reservations[*].Instances[*].InstanceId' \
  --output text)

if [[ -z "$INSTANCE_IDS" ]]; then
  echo "No EC2 instances found in region $REGION"
  exit 0
fi

echo "Terminating instances..."

aws ec2 terminate-instances \
  --region "$AWS_DEFAULT_REGION" \
  --instance-ids $INSTANCE_IDS

echo "Waiting for termination to complete..."

aws ec2 wait instance-terminated \
  --region "$AWS_DEFAULT_REGION" \
  --instance-ids $INSTANCE_IDS

echo "All EC2 instances have been terminated."

echo "Deleting Lambda functions..."

for name in naturecloud-fractals naturecloud-grayscott naturecloud-dna; do
    if aws lambda get-function --function-name "${name}" > /dev/null 2>&1; then
        echo "Deleting Lambda: ${name}"
        aws lambda delete-function --function-name "${name}"
    else
        echo "Lambda ${name} does not exist — skipping."
    fi
done
