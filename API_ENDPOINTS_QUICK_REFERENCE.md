# GraphRAG REST API - Quick Reference

## All Available Endpoints

### 1. Health Check
```powershell
Invoke-RestMethod -Uri http://localhost:8080/health
```
**Expected:** `OK`

---

### 2. Metadata (Graph Statistics)
```powershell
Invoke-RestMethod -Uri http://localhost:8080/v1/metadata
```
**Returns:** Node counts, edge counts, relation types, last updated timestamp

---

### 3. Query (Search Concepts)
```powershell
$body = @{
    query = "machine"
    topK = 10
} | ConvertTo-Json

$response = Invoke-RestMethod -Uri http://localhost:8080/v1/query -Method POST -Body $body -ContentType "application/json"

# Display results
$response.data.results | ForEach-Object {
    Write-Host "Concept: $($_.lemma) (ID: $($_.conceptId), Score: $($_.score))"
}
```
**Returns:** Matching concepts with IDs, scores, and related concepts

---

### 4. Evidence (Provenance)
```powershell
# First, get a concept ID from the query endpoint
$conceptId = "41e636ebb4669eae"  # Replace with actual concept ID

$evidence = Invoke-RestMethod -Uri "http://localhost:8080/v1/evidence/$conceptId"

# Display evidence
Write-Host "Concept: $($evidence.data.lemma)"
Write-Host "Total Mentions: $($evidence.data.totalMentions)"
$evidence.data.chunks | ForEach-Object {
    Write-Host "  - $($_.text.Substring(0, [Math]::Min(100, $_.text.Length)))..."
}
```
**Returns:** All text chunks that mention the concept, with source URIs and spans

---

### 5. Explore Neighbors
```powershell
# Use a concept ID from the query endpoint
$conceptId = "41e636ebb4669eae"  # Replace with actual concept ID

$neighbors = Invoke-RestMethod -Uri "http://localhost:8080/v1/graph/concept/$conceptId/neighbors?limit=10"

# Display neighbors
Write-Host "Center: $($neighbors.data.center.lemma)"
Write-Host "Total Neighbors: $($neighbors.data.totalNeighbors)"
$neighbors.data.neighbors | ForEach-Object {
    Write-Host "  - $($_.lemma) (ID: $($_.conceptId))"
}
```
**Returns:** Neighboring concepts and their relationships

---

### 6. Submit Async Job
```powershell
$body = @{
    query = "test query"
} | ConvertTo-Json

$job = Invoke-RestMethod -Uri http://localhost:8080/v1/jobs -Method POST -Body $body -ContentType "application/json"

Write-Host "Job ID: $($job.data.jobId)"
Write-Host "Status: $($job.data.state)"
```
**Returns:** Job ID and initial status

---

### 7. Check Job Status
```powershell
$jobId = "your-job-id-here"  # Replace with actual job ID

$status = Invoke-RestMethod -Uri "http://localhost:8080/v1/jobs/$jobId"

Write-Host "Job ID: $($status.data.jobId)"
Write-Host "State: $($status.data.state)"
Write-Host "Progress: $($status.data.progress)%"
```
**Returns:** Current job status and progress

---

### 8. Explain Trace (Execution Details)
```powershell
$requestId = "req-68b649ab"  # Replace with trace ID from query response

$explain = Invoke-RestMethod -Uri "http://localhost:8080/v1/explain/trace/$requestId"

Write-Host "Trace ID: $($explain.data.traceId)"
Write-Host "Execution Time: $($explain.data.executionTimeMs)ms"
```
**Returns:** Execution plan and performance metrics

---

## Complete Workflow Example

```powershell
# Step 1: Search for concepts
$body = @{ query = "machine" } | ConvertTo-Json
$query = Invoke-RestMethod -Uri http://localhost:8080/v1/query -Method POST -Body $body -ContentType "application/json"

# Step 2: Display all results
Write-Host "`n=== Query Results ===" -ForegroundColor Green
$query.data.results | ForEach-Object {
    Write-Host "  - $($_.lemma) (ID: $($_.conceptId), Score: $($_.score))" -ForegroundColor Yellow
}

# Step 3: Get evidence for first concept
if ($query.data.results.Count -gt 0) {
    $conceptId = $query.data.results[0].conceptId
    Write-Host "`n=== Evidence for $conceptId ===" -ForegroundColor Green
    
    $evidence = Invoke-RestMethod -Uri "http://localhost:8080/v1/evidence/$conceptId"
    Write-Host "Total Mentions: $($evidence.data.totalMentions)" -ForegroundColor Cyan
    $evidence.data.chunks | Select-Object -First 3 | ForEach-Object {
        Write-Host "`n  Chunk: $($_.text.Substring(0, [Math]::Min(150, $_.text.Length)))..." -ForegroundColor White
    }
    
    # Step 4: Explore neighbors
    Write-Host "`n=== Neighbors ===" -ForegroundColor Green
    $neighbors = Invoke-RestMethod -Uri "http://localhost:8080/v1/graph/concept/$conceptId/neighbors?limit=10"
    Write-Host "Total Neighbors: $($neighbors.data.totalNeighbors)" -ForegroundColor Cyan
    $neighbors.data.neighbors | ForEach-Object {
        Write-Host "  - $($_.lemma)" -ForegroundColor White
    }
}
```

---

## Quick Test All Endpoints

Run the test script:
```powershell
.\test-all-api-endpoints.ps1
```

Or test individually:
```powershell
# 1. Health
Invoke-RestMethod -Uri http://localhost:8080/health

# 2. Metadata
Invoke-RestMethod -Uri http://localhost:8080/v1/metadata

# 3. Query
$body = @{ query = "machine" } | ConvertTo-Json
Invoke-RestMethod -Uri http://localhost:8080/v1/query -Method POST -Body $body -ContentType "application/json"

# 4. Evidence (replace with actual concept ID)
Invoke-RestMethod -Uri "http://localhost:8080/v1/evidence/41e636ebb4669eae"

# 5. Neighbors (replace with actual concept ID)
Invoke-RestMethod -Uri "http://localhost:8080/v1/graph/concept/41e636ebb4669eae/neighbors?limit=10"
```

---

## Tips

1. **Search Terms:** Use single words or underscores (e.g., `"machine"` or `"machine_learning"`), not spaces
2. **Concept IDs:** Get them from the `/v1/query` endpoint results
3. **Job IDs:** Get them from the `/v1/jobs` POST response
4. **Trace IDs:** Get them from the `traceId` field in any API response

---

## Endpoint Summary Table

| # | Endpoint | Method | Purpose | Returns |
|---|----------|--------|---------|---------|
| 1 | `/health` | GET | Health check | `OK` |
| 2 | `/v1/metadata` | GET | Graph statistics | Node/edge counts, relation types |
| 3 | `/v1/query` | POST | Search concepts | **Concept IDs**, scores, related concepts |
| 4 | `/v1/evidence/:conceptId` | GET | Get provenance | Text chunks mentioning the concept |
| 5 | `/v1/graph/concept/:conceptId/neighbors` | GET | Explore graph | Neighboring concepts and relations |
| 6 | `/v1/jobs` | POST | Submit async job | Job ID and status |
| 7 | `/v1/jobs/:jobId` | GET | Check job status | Current job state and progress |
| 8 | `/v1/explain/trace/:requestId` | GET | Execution trace | Query execution details |

