#!/bin/bash

source config.sh

aws dynamodb delete-table --table-name naturecloud-regions
aws dynamodb delete-table --table-name naturecloud-requests