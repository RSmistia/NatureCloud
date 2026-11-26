#!/bin/bash

source config.sh

# Requesting an instance reboot.
aws ec2 reboot-instances --instance-ids $(cat instanceLBAS.id)
echo "Rebooting instance to test load balancer auto-start."

# Letting the instance shutdown.
sleep 1

# Wait for port 8080 to become available.
while ! nc -z $(cat instanceLBAS.dns) 8080; do
	echo "Waiting for $(cat instanceLBAS.dns):8080..."
	sleep 0.5
done

# Sending a query!
echo "Sending a query!"
curl http://$(cat instanceLBAS.dns):8080/test\?testing-after-reboot