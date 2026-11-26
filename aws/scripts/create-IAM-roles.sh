#!/bin/bash

source config.sh

aws iam create-role \
    --role-name naturecloud-worker-role \
    --assume-role-policy-document '{
                                       "Version": "2012-10-17",
                                       "Statement": [
                                           {
                                               "Effect": "Allow",
                                               "Principal": {
                                                   "Service": "ec2.amazonaws.com"
                                               },
                                               "Action": "sts:AssumeRole"
                                           },
                                           {
                                               "Effect": "Allow",
                                               "Principal": {
                                                   "Service": "lambda.amazonaws.com"
                                               },
                                               "Action": "sts:AssumeRole"
                                           }
                                       ]
                                   }'

aws iam attach-role-policy \
    --role-name naturecloud-worker-role \
    --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess_v2

aws iam attach-role-policy \
        --role-name naturecloud-worker-role \
        --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

aws iam create-instance-profile \
    --instance-profile-name naturecloud-worker-role

aws iam add-role-to-instance-profile \
    --instance-profile-name naturecloud-worker-role \
    --role-name naturecloud-worker-role

#------------------------------------------------------- LBAS Role -------------------------------------------------------

aws iam create-role \
    --role-name naturecloud-lbas-role \
    --assume-role-policy-document '{
                                       "Version": "2012-10-17",
                                       "Statement": [
                                           {
                                               "Effect": "Allow",
                                               "Principal": {
                                                   "Service": "ec2.amazonaws.com"
                                               },
                                               "Action": "sts:AssumeRole"
                                           }
                                       ]
                                   }'

aws iam attach-role-policy \
    --role-name naturecloud-lbas-role \
    --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess_v2

aws iam attach-role-policy \
    --role-name naturecloud-lbas-role \
    --policy-arn arn:aws:iam::aws:policy/AmazonEC2FullAccess

aws iam attach-role-policy \
    --role-name naturecloud-lbas-role \
    --policy-arn arn:aws:iam::aws:policy/CloudWatchReadOnlyAccess

aws iam put-role-policy \
    --role-name naturecloud-lbas-role \
    --policy-name PassWorkerRole \
    --policy-document '{
        "Version":"2012-10-17",
        "Statement":[
            {
                "Effect":"Allow",
                "Action":"iam:PassRole",
                "Resource":"arn:aws:iam::881839569902:role/naturecloud-worker-role",
                "Condition":{
                    "StringEquals":{
                        "iam:PassedToService":"ec2.amazonaws.com"
                    }
                }
            }
        ]
    }'

aws iam create-instance-profile \
    --instance-profile-name naturecloud-lbas-role

aws iam add-role-to-instance-profile \
    --instance-profile-name naturecloud-lbas-role \
    --role-name naturecloud-lbas-role