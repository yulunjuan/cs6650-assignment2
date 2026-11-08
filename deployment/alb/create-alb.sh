#!/bin/bash
# Creates the ALB, target group and listener with the AWS CLI.
# Fill in the variables below first. Same settings can be made in the EC2 console.
set -e

VPC_ID="REPLACE_vpc-id"
SUBNETS="REPLACE_subnet-a REPLACE_subnet-b"          # at least two AZs
ALB_SG="REPLACE_alb-security-group"              # inbound 80 from anywhere
INSTANCES="REPLACE_i-server1 REPLACE_i-server2"      # ingest servers (Tomcat on 8080)

TG_ARN=$(aws elbv2 create-target-group \
  --name skitrak-ingest --protocol HTTP --port 8080 --vpc-id "$VPC_ID" \
  --health-check-path /ski-servlet/health \
  --health-check-interval-seconds 30 \
  --health-check-timeout-seconds 5 \
  --healthy-threshold-count 2 \
  --unhealthy-threshold-count 3 \
  --query 'TargetGroups[0].TargetGroupArn' --output text)

# Sticky sessions: ALB-generated cookie pins a client to one target
aws elbv2 modify-target-group-attributes --target-group-arn "$TG_ARN" --attributes \
  Key=stickiness.enabled,Value=true \
  Key=stickiness.type,Value=lb_cookie \
  Key=stickiness.lb_cookie.duration_seconds,Value=86400

for ID in $INSTANCES; do
  aws elbv2 register-targets --target-group-arn "$TG_ARN" --targets Id="$ID"
done

ALB_ARN=$(aws elbv2 create-load-balancer \
  --name skitrak-alb --type application --subnets $SUBNETS --security-groups "$ALB_SG" \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text)

# WebSocket upgrades are supported by ALB out of the box; idle timeout must exceed 60s
aws elbv2 modify-load-balancer-attributes --load-balancer-arn "$ALB_ARN" --attributes \
  Key=idle_timeout.timeout_seconds,Value=120

aws elbv2 create-listener --load-balancer-arn "$ALB_ARN" --protocol HTTP --port 80 \
  --default-actions Type=forward,TargetGroupArn="$TG_ARN"

aws elbv2 describe-load-balancers --load-balancer-arns "$ALB_ARN" \
  --query 'LoadBalancers[0].DNSName' --output text
