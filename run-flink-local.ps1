# Run Flink Job Locally to Populate Neo4j Aura
# This script builds and runs the Flink GraphRAG job locally

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Flink GraphRAG Job - Local Execution" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# Check if chunks exist
$chunksPath = "phase1-delta-export/data/chunks.jsonl"
if (-not (Test-Path $chunksPath)) {
    Write-Host "❌ Error: Chunks not found at $chunksPath" -ForegroundColor Red
    Write-Host "   Please run Phase 1 export first" -ForegroundColor Yellow
    exit 1
}

Write-Host "✅ Chunks found at: $chunksPath" -ForegroundColor Green
Write-Host ""

# Check Neo4j Aura configuration
Write-Host "Neo4j Aura Configuration:" -ForegroundColor Cyan
Write-Host "  URI: neo4j+s://e86ce959.databases.neo4j.io" -ForegroundColor Gray
Write-Host "  Database: neo4j" -ForegroundColor Gray
Write-Host ""

# Build JAR
Write-Host "Building JAR file..." -ForegroundColor Cyan
sbt assembly

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Build failed!" -ForegroundColor Red
    exit 1
}

Write-Host "✅ Build successful" -ForegroundColor Green
Write-Host ""

# Run Flink job
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Running Flink Job..." -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Input: $chunksPath" -ForegroundColor Gray
Write-Host "Output: Neo4j Aura" -ForegroundColor Gray
Write-Host ""
Write-Host "This will:" -ForegroundColor Yellow
Write-Host "  1. Read chunks from $chunksPath" -ForegroundColor White
Write-Host "  2. Extract concepts" -ForegroundColor White
Write-Host "  3. Find co-occurrences" -ForegroundColor White
Write-Host "  4. Score relations (if Ollama available)" -ForegroundColor White
Write-Host "  5. Write to Neo4j Aura" -ForegroundColor White
Write-Host ""
Write-Host "Press Ctrl+C to cancel, or wait for job to start..." -ForegroundColor Yellow
Start-Sleep -Seconds 3
Write-Host ""

# Run the job
sbt "runMain graphrag.GraphRagJob --input $chunksPath"

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Green
    Write-Host "✅ Job completed successfully!" -ForegroundColor Green
    Write-Host "==========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Cyan
    Write-Host "  1. Verify data in Neo4j Aura dashboard" -ForegroundColor White
    Write-Host "  2. Start REST API: sbt `"runMain graphrag.api.ApiServer`"" -ForegroundColor White
    Write-Host "  3. Test endpoints: http://localhost:8080/v1/metadata" -ForegroundColor White
} else {
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Red
    Write-Host "❌ Job failed. Check logs above." -ForegroundColor Red
    Write-Host "==========================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Common issues:" -ForegroundColor Yellow
    Write-Host "  - Neo4j Aura connection failed (check credentials)" -ForegroundColor White
    Write-Host "  - Chunks not found (verify path)" -ForegroundColor White
    Write-Host "  - Out of memory (increase heap size)" -ForegroundColor White
    exit 1
}



