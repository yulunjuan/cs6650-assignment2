#!/bin/bash
# deploy-server.sh copies this to <tomcat>/bin/skitrak-env.sh and sources it from setenv.sh.
export RABBIT_HOST="REPLACE_rabbitmq-private-ip"
export RABBIT_USER=skitrak
export RABBIT_PASS=change-me
export CHANNEL_POOL_SIZE=64
# SERVER_ID is not set here: it defaults to the EC2 hostname, which is unique per instance.
