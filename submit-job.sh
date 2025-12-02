#!/bin/bash
# Submit GraphRAG job to Flink cluster (use with WSL)

FLINK_HOME="/mnt/c/Users/harsh/Downloads/flink-1.20.3-bin-scala_2.12/flink-1.20.3"
PROJECT_ROOT="/mnt/c/Users/harsh/IdeaProjects/cs441-hw3-rag"
JAR_FILE="$PROJECT_ROOT/target/scala-2.12/cs441-hw3-graphrag-assembly-0.1.0-SNAPSHOT.jar"
INPUT_PATH="file://$PROJECT_ROOT/phase1-delta-export/data/"

if [ ! -d "$FLINK_HOME" ]; then
    echo "Flink not found at $FLINK_HOME!"
    exit 1
fi

if [ ! -f "$JAR_FILE" ]; then
    echo "JAR file not found! Please run: sbt assembly"
    exit 1
fi

echo "Submitting GraphRAG job to Flink..."
echo "Input path: $INPUT_PATH"
echo ""

cd "$FLINK_HOME"
./bin/flink run \
  -c graphrag.GraphRagJob \
  "$JAR_FILE" \
  --input "$INPUT_PATH"

echo ""
echo "Job submitted! Check status at: http://localhost:8081"








