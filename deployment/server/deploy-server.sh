#!/bin/bash
# Usage: ./deploy-server.sh <key.pem> <ec2-host> [<ec2-host> ...]
# Builds server-v2 once and deploys the WAR + RabbitMQ settings to each host, then restarts Tomcat.
set -e

# Absolute key path, so it still resolves after the cd below
KEY="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
shift
cd "$(dirname "$0")/../../server-v2"
# MVN can point at a Maven that is not on PATH (e.g. the one bundled with IntelliJ)
"${MVN:-mvn}" -q clean package

for HOST in "$@"; do
  echo "Deploying to $HOST"
  scp -i "$KEY" target/ski-servlet.war ec2-user@"$HOST":/tmp/ski-servlet.war
  # Separate file, sourced from setenv.sh, so an existing setenv.sh (e.g. JAVA_HOME) is kept
  scp -i "$KEY" ../deployment/server/setenv.sh ec2-user@"$HOST":/opt/tomcat9/bin/skitrak-env.sh
  ssh -i "$KEY" ec2-user@"$HOST" '
    touch /opt/tomcat9/bin/setenv.sh
    grep -q skitrak-env.sh /opt/tomcat9/bin/setenv.sh || \
      echo ". /opt/tomcat9/bin/skitrak-env.sh" >> /opt/tomcat9/bin/setenv.sh
    /opt/tomcat9/bin/shutdown.sh || true
    sleep 3
    rm -rf /opt/tomcat9/webapps/ski-servlet /opt/tomcat9/webapps/ski-servlet.war
    mv /tmp/ski-servlet.war /opt/tomcat9/webapps/ski-servlet.war
    /opt/tomcat9/bin/startup.sh'
done
