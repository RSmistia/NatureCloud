#!/bin/bash

source config.sh

# Install java.
cmd="sudo yum update -y; sudo yum install java-11-amazon-corretto-devel.x86_64 -y;"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAIR_PATH ec2-user@$(cat instanceLBAS.dns) $cmd

# Install load balancer.
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAIR_PATH -r $DIR/../../loadbalancer ec2-user@$(cat instanceLBAS.dns):~

# Setup load balancer to start on instance launch.
cmd="echo -e '#!/bin/bash\\njava -cp /home/ec2-user/loadbalancer/target/loadbalancer-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.naturecloud.loadbalancer.Main ' | sudo tee /etc/rc.d/rc.local; sudo chmod +x /etc/rc.d/rc.local"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAIR_PATH ec2-user@$(cat instanceLBAS.dns) $cmd


