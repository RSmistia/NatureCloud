#!/bin/bash

source config.sh

aws ec2 run-instances \
    --image-id "$(cat imageLBAS.id)" \
    --instance-type t3.micro \
    --key-name $AWS_KEYPAIR_NAME \
    --security-group-ids $AWS_SECURITY_GROUP \
    --iam-instance-profile Name=naturecloud-lbas-role \
    --monitoring Enabled=true

FRACTALS_JAR="$DIR/../../fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
GRAYSCOTT_JAR="$DIR/../../grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
DNA_JAR="$DIR/../../dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

ROLE_ARN="arn:aws:iam::${AWS_ACCOUNT_ID}:role/naturecloud-worker-role"

MEMORY_MB=512
TIMEOUT_SEC=120

deploy_lambda() {
    local name="$1"
    local handler="$2"
    local jar="$3"

    if [ ! -f "${jar}" ]; then
        echo "ERROR: JAR not found: ${jar}"
        echo "       Run 'mvn clean package' from the repo root first."
        exit 1
    fi

    echo ""
    echo "=== Deploying Lambda: ${name} ==="

    aws lambda create-function \
            --function-name "${name}" \
            --zip-file "fileb://${jar}" \
            --handler "${handler}" \
            --runtime java11 \
            --timeout "${TIMEOUT_SEC}" \
            --memory-size "${MEMORY_MB}" \
            --role "${ROLE_ARN}"

    aws lambda wait function-active --function-name "${name}"
    echo "${name} is Active."
}

deploy_lambda \
    "naturecloud-fractals" \
    "com.naturecloud.fractals.FractalsHandler::handleRequest" \
    "${FRACTALS_JAR}"

deploy_lambda \
    "naturecloud-grayscott" \
    "com.naturecloud.grayscott.GrayScottHandler::handleRequest" \
    "${GRAYSCOTT_JAR}"

deploy_lambda \
    "naturecloud-dna" \
    "com.naturecloud.dna.DnaHandler::handleRequest" \
    "${DNA_JAR}"

echo "All Lambda functions deployed successfully."