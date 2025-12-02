#!/bin/bash
# Run Flink Job in WSL to Populate Neo4j Aura
# This script builds and runs the Flink GraphRAG job in WSL

set -e

echo "=========================================="
echo "Flink GraphRAG Job - WSL Execution"
echo "=========================================="
echo ""

# Get project root (works in WSL)
PROJECT_ROOT="/mnt/c/Users/harsh/IdeaProjects/cs441-hw3-rag"
cd "$PROJECT_ROOT"

# Check if chunks exist
CHUNKS_PATH="phase1-delta-export/data/chunks.jsonl"
if [ ! -d "$CHUNKS_PATH" ]; then
    echo "❌ Error: Chunks not found at $CHUNKS_PATH"
    echo "   Please run Phase 1 export first"
    exit 1
fi

echo "✅ Chunks found at: $CHUNKS_PATH"
echo ""

# Check Neo4j Aura configuration
echo "Neo4j Aura Configuration:"
echo "  URI: neo4j+s://e86ce959.databases.neo4j.io"
echo "  Database: neo4j"
echo ""

# Build JAR
echo "Building JAR file..."
sbt assembly

if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

echo "✅ Build successful"
echo ""

# Run Flink job
echo "=========================================="
echo "Running Flink Job..."
echo "=========================================="
echo ""
echo "Input: $CHUNKS_PATH"
echo "Output: Neo4j Aura"
echo ""
echo "This will:"
echo "  1. Read chunks from $CHUNKS_PATH"
echo "  2. Extract concepts"
echo "  3. Find co-occurrences"
echo "  4. Score relations (if Ollama available)"
echo "  5. Write to Neo4j Aura"
echo ""
echo "Starting job in 3 seconds..."
sleep 3
echo ""

# Run the job
sbt "runMain graphrag.GraphRagJob --input $CHUNKS_PATH"

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "✅ Job completed successfully!"
    echo "=========================================="
    echo ""
    echo "Next steps:"
    echo "  1. Verify data in Neo4j Aura dashboard"
    echo "  2. Start REST API: sbt \"runMain graphrag.api.ApiServer\""
    echo "  3. Test endpoints: http://localhost:8080/v1/metadata"
else
    echo ""
    echo "=========================================="
    echo "❌ Job failed. Check logs above."
    echo "=========================================="
    echo ""
    echo "Common issues:"
    echo "  - Neo4j Aura connection failed (check credentials)"
    echo "  - Chunks not found (verify path)"
    echo "  - Out of memory (increase heap size)"
    exit 1
fi



