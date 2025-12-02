#!/bin/bash
# Start Apache Flink cluster (use with WSL)

FLINK_HOME="/mnt/c/Users/harsh/Downloads/flink-1.20.3-bin-scala_2.12/flink-1.20.3"

if [ ! -d "$FLINK_HOME" ]; then
    echo "Flink not found at $FLINK_HOME!"
    exit 1
fi

echo "Starting Flink cluster..."
cd "$FLINK_HOME"
./bin/start-cluster.sh

echo ""
echo "Flink cluster started!"
echo "Web UI available at: http://localhost:8081"
echo ""
echo "To submit a job, run: ./submit-job.sh"








