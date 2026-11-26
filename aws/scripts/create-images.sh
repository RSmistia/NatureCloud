#!/bin/bash

source config.sh
# Step 1: launch a vm instance.
$DIR/createImageWeb/launch-vm.sh

# Step 2: install software in the VM instance.
$DIR/createImageWeb/install-vm.sh

# Step 3: test VM instance.
$DIR/createImageWeb/test-vm.sh

# Step 4: create VM image (AIM).
aws ec2 create-image --instance-id $(cat instance.id) --name NatureCloud-Worker-Image | jq -r .ImageId > image.id
echo "New VM image with id $(cat image.id)."

# Step 5: Wait for image to become available.
echo "Waiting for image to be ready... (this can take a couple of minutes)"
aws ec2 wait image-available --filters Name=name,Values=NatureCloud-Worker-Image
echo "Waiting for image to be ready... done! \o/"

# Step 6: terminate the vm instance.
aws ec2 terminate-instances --instance-ids $(cat instance.id)

#LOAD BALANCER AND AUTO SCALER MACHINE IMAGE

# Step 1: launch a vm instance.
$DIR/createImageLBAS/launch-vm.sh

# Step 2: install software in the VM instance.
$DIR/createImageLBAS/install-vm.sh

# Step 3: test VM instance.
$DIR/createImageLBAS/test-vm.sh

# Step 4: create VM image (AIM).
aws ec2 create-image --instance-id $(cat instanceLBAS.id) --name NatureCloud-LBAS-Image | jq -r .ImageId > imageLBAS.id
echo "New VM image with id $(cat imageLBAS.id)."

# Step 5: Wait for image to become available.
echo "Waiting for image to be ready... (this can take a couple of minutes)"
aws ec2 wait image-available --filters Name=name,Values=NatureCloud-LBAS-Image
echo "Waiting for image to be ready... done! \o/"

# Step 6: terminate the vm instance.
aws ec2 terminate-instances --instance-ids $(cat instanceLBAS.id)

$DIR/delete-dynamo.sh

