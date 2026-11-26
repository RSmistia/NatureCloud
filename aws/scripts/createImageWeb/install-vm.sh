#!/bin/bash

source config.sh

# Install java.
cmd="sudo yum update -y; sudo yum install java-11-amazon-corretto-devel.x86_64 -y;"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAIR_PATH ec2-user@$(cat instance.dns) $cmd

# Install web server.
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAIR_PATH -r $DIR/../../webserver $DIR/../../javassist ec2-user@$(cat instance.dns):~

# Setup web server to start on instance launch.
cmd="echo -e '#!/bin/bash\\njava -cp /home/ec2-user/webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar -Xbootclasspath/a:/home/ec2-user/javassist/target/javassist-1.0.0-SNAPSHOT-jar-with-dependencies.jar -javaagent:/home/ec2-user/webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar=ICount:com.naturecloud.fractals,com.naturecloud.grayscott,com.naturecloud.dna:/home/ec2-user/output com.naturecloud.webserver.WebServer' | sudo tee /etc/rc.d/rc.local; sudo chmod +x /etc/rc.d/rc.local"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAIR_PATH ec2-user@$(cat instance.dns) $cmd


