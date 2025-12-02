# GraphRAG REST API Documentation

## Overview

This REST API provides access to the GraphRAG knowledge graph built from the MSR corpus. It exposes 6 microservices for querying concepts, exploring relationships, and retrieving evidence with provenance.

**Base URL:** `http://localhost:8080`

---

## Endpoints

### 1. Health Check

**Endpoint:** `GET /health`

**Description:** Simple health check to verify the API is running.

**Response:**
```
OK
```

**Example:**
```bash
curl http://localhost:8080/health
```

---

### 2. Metadata Service

**Endpoint:** `GET /v1/metadata`

**Description:** Returns graph statistics including node counts, edge counts, and relationship type distribution.

**Response:**
```json
{
  "success": true,
  "data": {
    "nodeCount": 305,
    "edgeCount": 4376,
    "conceptCount": 275,
    "chunkCount": 30,
    "relationTypes": {
      "IS_A": 3448,
      "SYNONYM_OF": 590,
      "RELATED_TO": 338
    },
    "lastUpdated": "2025-11-21T12:34:56.789Z"
  },
  "traceId": "uuid-here"
}
```

**Example:**
```bash
curl http://localhost:8080/v1/metadata
```

---

### 3. Evidence Service

**Endpoint:** `GET /v1/evidence/:conceptId`

**Description:** Returns all text chunks that mention a specific concept (provenance). This allows you to trace back from a concept to the original source text.

**Path Parameters:**
- `conceptId` (string, required): The unique ID of the concept

**Response:**
```json
{
  "success": true,
  "data": {
    "conceptId": "3fa85cd1e29f47b2",
    "lemma": "machine_learning",
    "surface": "Machine Learning",
    "chunks": [
      {
        "chunkId": "050289be...",
        "docId": "doc123",
        "text": "Machine Learning improves API performance...",
        "sourceUri": "msr2020-paper.pdf",
        "span": {
          "start": 0,
          "end": 500
        }
      }
    ],
    "totalMentions": 10
  },
  "traceId": "uuid-here"
}
```

**Example:**
```bash
curl http://localhost:8080/v1/evidence/3fa85cd1e29f47b2
```

---

### 4. Explore Service

**Endpoint:** `GET /v1/explore/:conceptId`

**Description:** Returns neighboring concepts and their relationships. Use this to navigate the knowledge graph.

**Path Parameters:**
- `conceptId` (string, required): The unique ID of the concept

**Query Parameters:**
- `limit` (integer, optional, default=20): Maximum number of neighbors to return

**Response:**
```json
{
  "success": true,
  "data": {
    "center": {
      "conceptId": "3fa85cd1e29f47b2",
      "lemma": "machine_learning",
      "surface": "Machine Learning",
      "origin": "NER"
    },
    "neighbors": [
      {
        "conceptId": "5e8f3a2b9c4d1e7f",
        "lemma": "api",
        "surface": "API",
        "origin": "acronym"
      }
    ],
    "relations": [
      {
        "fromConceptId": "3fa85cd1e29f47b2",
        "toConceptId": "5e8f3a2b9c4d1e7f",
        "relationType": "RELATED_TO",
        "confidence": 0.85,
        "evidence": "Machine Learning improves API..."
      }
    ],
    "totalNeighbors": 15
  },
  "traceId": "uuid-here"
}
```

**Example:**
```bash
curl "http://localhost:8080/v1/explore/3fa85cd1e29f47b2?limit=10"
```

---

### 5. Query Service

**Endpoint:** `POST /v1/query`

**Description:** Search for concepts matching a text query. Returns concepts and their related concepts.

**Request Body:**
```json
{
  "query": "machine learning",
  "timeRange": {
    "from": 2018,
    "to": 2025
  },
  "constraints": {
    "datasets": ["JITGIT"],
    "baselines": ["Random Forest"],
    "minConfidence": 0.7
  },
  "output": {
    "groupBy": ["techFamily"],
    "metrics": ["AUC"],
    "topKPerGroup": 5,
    "includeCitations": true
  }
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "mode": "sync",
    "summary": "Found 5 concepts matching 'machine learning'",
    "results": [
      {
        "conceptId": "3fa85cd1e29f47b2",
        "lemma": "machine_learning",
        "relatedConcepts": ["api", "performance", "neural_network"],
        "score": 15.0
      }
    ]
  },
  "traceId": "uuid-here"
}
```

**Example:**
```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{"query":"machine_learning"}'
```

---

### 6. Jobs Service

**Endpoint (Submit):** `POST /v1/jobs`

**Description:** Submit an asynchronous query job for long-running queries.

**Request Body:** Same as Query Service

**Response:**
```json
{
  "success": true,
  "data": {
    "jobId": "job-8a1c3",
    "state": "PENDING",
    "startedAt": "2025-11-21T12:34:56.789Z",
    "finishedAt": null,
    "progress": 0,
    "resultLink": "/v1/jobs/job-8a1c3/result",
    "error": null
  },
  "traceId": "uuid-here"
}
```

**Endpoint (Status):** `GET /v1/jobs/:jobId`

**Description:** Check the status of an async job.

**Response:**
```json
{
  "success": true,
  "data": {
    "jobId": "job-8a1c3",
    "state": "SUCCEEDED",
    "startedAt": "2025-11-21T12:34:56.789Z",
    "finishedAt": "2025-11-21T12:35:00.123Z",
    "progress": 100,
    "resultLink": "/v1/jobs/job-8a1c3/result",
    "error": null
  },
  "traceId": "uuid-here"
}
```

**Endpoint (Result):** `GET /v1/jobs/:jobId/result`

**Description:** Fetch the result of a completed job.

**Example:**
```bash
# Submit job
JOB_ID=$(curl -s -X POST http://localhost:8080/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{"query":"test"}' | jq -r '.data.jobId')

# Check status
curl http://localhost:8080/v1/jobs/$JOB_ID

# Get result
curl http://localhost:8080/v1/jobs/$JOB_ID/result
```

---

### 7. Explain Service

**Endpoint:** `GET /v1/explain/trace/:requestId`

**Description:** Returns the execution trace for a query, showing how it was executed (Cypher queries, counters, timing).

**Response:**
```json
{
  "success": true,
  "data": {
    "requestId": "req-1a2b",
    "query": "machine_learning",
    "plan": [
      {
        "step": "matchConcepts",
        "cypher": "MATCH (c:Concept) WHERE c.lemma CONTAINS $pattern",
        "detail": "Find concepts matching pattern"
      },
      {
        "step": "getRelations",
        "cypher": "MATCH (c)-[r]-(related:Concept)",
        "detail": "Get related concepts"
      }
    ],
    "counters": {
      "nodesRead": 275,
      "relationshipsRead": 4376,
      "llmCalls": 0,
      "cacheHits": 1
    },
    "executionTimeMs": 45
  },
  "traceId": "uuid-here"
}
```

**Example:**
```bash
curl http://localhost:8080/v1/explain/trace/req-1a2b
```

---

## Error Responses

All endpoints return errors in a consistent format:

```json
{
  "success": false,
  "error": {
    "code": "CONCEPT_NOT_FOUND",
    "message": "Concept not found: xyz",
    "details": "java.util.NoSuchElementException: ..."
  }
}
```

**Common Error Codes:**
- `CONCEPT_NOT_FOUND` (404): Concept ID not in database
- `JOB_NOT_FOUND` (404): Job ID not found
- `QUERY_ERROR` (500): Query execution failed
- `INTERNAL_ERROR` (500): Unexpected server error

---

## Testing with curl

### Get graph statistics:
```bash
curl http://localhost:8080/v1/metadata
```

### Get evidence for a concept:
```bash
# First, get a concept ID from metadata or query
curl http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{"query":"machine"}' | jq -r '.data.results[0].conceptId'

# Then get evidence
curl http://localhost:8080/v1/evidence/<conceptId>
```

### Explore concept neighbors:
```bash
curl "http://localhost:8080/v1/explore/<conceptId>?limit=5"
```

### Search for concepts:
```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{"query":"neural"}'
```

---

## Running the API Server

### Prerequisites:
1. Neo4j running with `graphrag` database
2. Knowledge graph populated (run Flink job first)

### Start the server:
```bash
# Windows
run-api-server.bat

# Or with sbt directly
sbt "runMain graphrag.api.ApiServer"
```

### Test the server:
```bash
# Windows
test-api.bat

# Or manually with curl
curl http://localhost:8080/health
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    API Server                           │
│                 (Akka HTTP 10.5.3)                      │
└─────────────────────────────────────────────────────────┘
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
   ┌────▼────┐      ┌────▼────┐      ┌────▼────┐
   │Metadata │      │Evidence │      │Explore  │
   │ Service │      │ Service │      │ Service │
   └────┬────┘      └────┬────┘      └────┬────┘
        │                 │                 │
   ┌────▼────┐      ┌────▼────┐      ┌────▼────┐
   │  Query  │      │  Jobs   │      │ Explain │
   │ Service │      │ Service │      │ Service │
   └────┬────┘      └────┬────┘      └────┬────┘
        │                 │                 │
        └─────────────────┼─────────────────┘
                          │
               ┌──────────▼──────────┐
               │  Neo4jQueryService  │
               │  (Database Layer)   │
               └──────────┬──────────┘
                          │
                     ┌────▼────┐
                     │  Neo4j  │
                     │ Database│
                     └─────────┘
```

---

## Implementation Notes

- **Framework:** Akka HTTP 10.5.3 with Scala 2.12
- **JSON:** spray-json for serialization
- **Database:** Neo4j Java Driver 5.15.0
- **Concurrency:** Akka Streams for non-blocking I/O
- **Error Handling:** Try/Either pattern with graceful fallbacks
- **Jobs Service:** Simplified in-memory implementation (production would use Kafka/Flink)
- **Explain Service:** Mock traces (production would log actual execution)

---

## Future Enhancements

1. **Authentication:** OAuth 2.0 / JWT tokens
2. **Rate Limiting:** Per-user request limits
3. **Caching:** Redis for frequently accessed concepts
4. **Pagination:** Cursor-based pagination for large result sets
5. **GraphQL:** Alternative to REST for complex queries
6. **WebSockets:** Real-time graph updates
7. **Metrics:** Prometheus/Grafana for monitoring
8. **Docker:** Containerized deployment
9. **AWS Deployment:** EKS/ECS for production scaling






















