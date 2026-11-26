#!/bin/bash

source config.sh

aws iam detach-role-policy \
    --role-name naturecloud-worker-role \
    --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess_v2

aws iam detach-role-policy \
    --role-name naturecloud-worker-role \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

aws iam remove-role-from-instance-profile \
    --instance-profile-name naturecloud-worker-role \
    --role-name naturecloud-worker-role

# Delete instance profile
aws iam delete-instance-profile \
    --instance-profile-name naturecloud-worker-role

aws iam delete-role \
    --role-name naturecloud-worker-role


#------------------------------------------------------- LBAS Role -------------------------------------------------------

aws iam detach-role-policy \
    --role-name naturecloud-lbas-role \
    --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess_v2

aws iam detach-role-policy \
    --role-name naturecloud-lbas-role \
    --policy-arn arn:aws:iam::aws:policy/AmazonEC2FullAccess

aws iam detach-role-policy \
    --role-name naturecloud-lbas-role \
    --policy-arn arn:aws:iam::aws:policy/CloudWatchReadOnlyAccess

aws iam delete-role-policy \
    --role-name naturecloud-lbas-role \
    --policy-name PassWorkerRole

aws iam remove-role-from-instance-profile \
    --instance-profile-name naturecloud-lbas-role \
    --role-name naturecloud-lbas-role

# Delete instance profile
aws iam delete-instance-profile \
    --instance-profile-name naturecloud-lbas-role

aws iam delete-role \
    --role-name naturecloud-lbas-role