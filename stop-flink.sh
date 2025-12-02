#!/bin/bash
# Stop Apache Flink cluster (use with WSL)

FLINK_HOME="/mnt/c/Users/harsh/Downloads/flink-1.20.3-bin-scala_2.12/flink-1.20.3"

if [ ! -d "$FLINK_HOME" ]; then
    echo "Flink not found at $FLINK_HOME!"
    exit 1
fi

echo "Stopping Flink cluster..."
cd "$FLINK_HOME"
./bin/stop-cluster.sh

echo "Flink cluster stopped!"








