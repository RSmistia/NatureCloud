#!/bin/bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

export PATH=$DIR/../bin:$PATH
export AWS_DEFAULT_REGION=<your-aws-region>
export AWS_ACCOUNT_ID=<your-aws-account-id>
export AWS_ACCESS_KEY_ID=<your-access-key-id>
export AWS_SECRET_ACCESS_KEY=<your-secret-access-key>
export AWS_EC2_SSH_KEYPAIR_PATH=<path-to-your-ssh-keypair>
export AWS_SECURITY_GROUP=<your-security-group-id>
export AWS_KEYPAIR_NAME=<your-keypair-name>