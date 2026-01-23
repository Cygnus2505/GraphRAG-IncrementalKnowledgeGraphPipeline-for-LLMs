# GraphRAG: Knowledge Graph Construction and Query System

A comprehensive GraphRAG (Graph Retrieval-Augmented Generation) system that extracts knowledge from research papers, builds a knowledge graph in Neo4j, and exposes it through a REST API for semantic search and exploration.

##  Video Demonstration

Watch the running implementation: [YouTube Video](https://youtu.be/utOL2YcOrVs)

##  Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Local Setup](#local-setup)
- [Cloud Deployment (AWS EKS)](#cloud-deployment-aws-eks)
- [REST API Usage](#rest-api-usage)
- [Configuration](#configuration)
- [Troubleshooting](#troubleshooting)
- [Project Structure](#project-structure)

---

##  Overview

This system implements a complete GraphRAG pipeline that:

1. **Processes Text Chunks**: Reads text chunks from S3 or local files
2. **Extracts Concepts**: Uses NLP (NER, acronyms, heuristics) to identify concepts
3. **Discovers Relationships**: Finds relationships between concepts using co-occurrence and LLM scoring
4. **Builds Knowledge Graph**: Stores concepts and relationships in Neo4j
5. **Exposes REST API**: Provides 8 endpoints for querying and exploring the graph

### Key Features

- **Scalable Processing**: Apache Flink for distributed stream processing
- **LLM Integration**: Ollama with TinyLlama for relationship scoring
- **Graph Database**: Neo4j for efficient graph storage and traversal
- **REST API**: Akka HTTP server with 8 comprehensive endpoints
- **Provenance Tracking**: Full traceability from concepts to source text
- **Cloud Ready**: Deployable on AWS EKS with S3 integration

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Data Sources                              │
│  (S3 Bucket / Local Files: chunks.jsonl)                    │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              Apache Flink Pipeline                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │
│  │   Extract    │→ │  Discover    │→ │   Score      │    │
│  │   Concepts   │  │  Relations   │  │  Relations   │    │
│  └──────────────┘  └──────────────┘  └──────────────┘    │
│         │                  │                  │            │
│         └──────────────────┴──────────────────┘            │
│                            │                               │
│                            ▼                               │
│                    ┌──────────────┐                        │
│                    │   Neo4j     │                        │
│                    │  Graph DB   │                        │
│                    └──────────────┘                        │
└─────────────────────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              REST API Server (Akka HTTP)                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐     │
│  │ Metadata │ │  Query   │ │ Evidence │ │ Explore  │     │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐     │
│  │  Jobs   │ │  Status  │ │ Explain  │ │  Health  │     │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘     │
└─────────────────────────────────────────────────────────────┘
```

### Components

1. **Flink Pipeline** (`GraphRagJob.scala`)
   - Reads chunks from S3/local files
   - Extracts concepts using NLP
   - Discovers relationships via co-occurrence
   - Scores relationships using Ollama LLM
   - Writes to Neo4j

2. **Neo4j Database**
   - Stores concepts as nodes
   - Stores relationships as edges (IS_A, SYNONYM_OF, RELATED_TO)
   - Maintains provenance links to source chunks

3. **REST API** (`ApiServer.scala`)
   - 8 endpoints for querying the graph
   - JSON responses with structured data
   - Error handling and logging

4. **Ollama LLM** (Optional)
   - Scores relationship confidence
   - Uses TinyLlama model for efficiency

---

##  Prerequisites

### For Local Development

- **Java 17+** (OpenJDK or Oracle JDK)
- **Scala 2.12** (via SBT)
- **SBT 1.9+** (Scala Build Tool)
- **Neo4j** (local instance or Neo4j Aura)
- **Ollama** (optional, for LLM relationship scoring)
- **Docker** (optional, for containerized deployment)

### For Cloud Deployment

- **AWS Account** with appropriate permissions
- **AWS CLI** configured
- **kubectl** installed and configured for EKS
- **Docker** for building images
- **ECR Repository** for storing Docker images
- **EKS Cluster** with Flink Kubernetes Operator installed
- **S3 Bucket** for input data and Flink checkpoints

### Software Installation

#### Install Java 17
```bash
# Windows (using Chocolatey)
choco install openjdk17

# macOS (using Homebrew)
brew install openjdk@17

# Linux (Ubuntu/Debian)
sudo apt-get install openjdk-17-jdk
```

#### Install SBT
```bash
# Windows (using Chocolatey)
choco install sbt

# macOS (using Homebrew)
brew install sbt

# Linux
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
sudo apt-get update
sudo apt-get install sbt
```

#### Install Neo4j
```bash
# Option 1: Local Neo4j Desktop
# Download from https://neo4j.com/download/

# Option 2: Docker
docker run -d \
  --name neo4j \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/your-password \
  neo4j:latest

# Option 3: Neo4j Aura (Cloud)
# Sign up at https://neo4j.com/cloud/aura/
```

#### Install Ollama (Optional)
```bash
# Windows/macOS/Linux
# Download from https://ollama.ai/download

# After installation, pull the model
ollama pull tinyllama
```

---

##  Local Setup

### Step 1: Clone and Build

```bash
# Clone the repository
git clone <your-repo-url>
cd cs441-hw3-rag

# Build the project
sbt clean compile

# Create the assembly JAR
sbt assembly
```

The JAR will be created at: `target/scala-2.12/cs441-hw3-graphrag-assembly-0.1.0-SNAPSHOT.jar`

### Step 2: Configure Neo4j

Edit `src/main/resources/application.conf`:

```hocon
neo4j {
  uri = "bolt://localhost:7687"  # or "neo4j+s://your-aura-instance.databases.neo4j.io"
  user = "neo4j"
  password = "your-password"
  database = "neo4j"
}
```

Or set environment variables:
```bash
export NEO4J_URI="bolt://localhost:7687"
export NEO4J_USER="neo4j"
export NEO4J_PASS="your-password"
```

### Step 3: Configure Ollama (Optional)

If using Ollama for relationship scoring:

```hocon
ollama {
  endpoint = "http://localhost:11434"
  model = "tinyllama:latest"
  timeout-ms = 60000
}
```

Or set environment variable:
```bash
export OLLAMA_ENDPOINT="http://localhost:11434"
```

### Step 4: Prepare Input Data

Place your `chunks.jsonl` file in one of these locations:

**Option A: Local File**
```bash
# Create directory
mkdir -p phase1-delta-export/data/chunks.jsonl

# Copy your chunks.jsonl file there
cp your-chunks.jsonl phase1-delta-export/data/chunks.jsonl/
```

**Option B: S3 Bucket**
```bash
# Upload to S3
aws s3 cp your-chunks.jsonl s3://your-bucket/chunks/chunks.jsonl
```

### Step 5: Run Flink Job Locally

**Option A: Using SBT (Embedded Flink)**
```bash
sbt "runMain graphrag.GraphRagJob --input phase1-delta-export/data/chunks.jsonl"
```

**Option B: Using Flink Cluster (WSL/Linux)**
```bash
# Start Flink cluster
./start-flink.sh

# Submit job
./submit-job.sh

# Monitor at http://localhost:8081
```

**Option C: Using Docker**
```bash
# Build Docker image
docker build -f Dockerfile.flink -t graphrag-flink:latest .

# Run with local files
docker run -v $(pwd)/phase1-delta-export/data:/data \
  -e NEO4J_URI="bolt://host.docker.internal:7687" \
  -e NEO4J_PASS="your-password" \
  graphrag-flink:latest
```

### Step 6: Start REST API Server

```bash
# Using SBT
sbt "runMain graphrag.api.ApiServer"

# Or using the batch script (Windows)
.\run-api-server.bat

# Server will start on http://localhost:8080
```

### Step 7: Test the API

```powershell
# Health check
Invoke-RestMethod -Uri http://localhost:8080/health

# Get metadata
Invoke-RestMethod -Uri http://localhost:8080/v1/metadata

# Query concepts
$body = @{ query = "machine" } | ConvertTo-Json
Invoke-RestMethod -Uri http://localhost:8080/v1/query -Method POST -Body $body -ContentType "application/json"
```

See [API_ENDPOINTS_QUICK_REFERENCE.md](API_ENDPOINTS_QUICK_REFERENCE.md) for complete API documentation.

---

## ☁️ Cloud Deployment (AWS EKS)

### Prerequisites

1. **AWS Account** with EKS, ECR, and S3 access
2. **EKS Cluster** created and configured
3. **Flink Kubernetes Operator** installed
4. **ECR Repository** for Docker images
5. **S3 Bucket** for input data and checkpoints

### Step 1: Configure AWS CLI

```bash
aws configure
# Enter your AWS Access Key ID
# Enter your AWS Secret Access Key
# Enter default region (e.g., us-east-1)
# Enter default output format (json)
```

### Step 2: Create ECR Repository

```bash
# Set variables
export AWS_ACCOUNT_ID="405721655991"
export AWS_REGION="us-east-1"
export ECR_REPO="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/graphrag-flink"

# Create repository
aws ecr create-repository --repository-name graphrag-flink --region $AWS_REGION

# Login to ECR
aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REPO
```

### Step 3: Build and Push Docker Image

```bash
# Build JAR
sbt assembly
cp target/scala-2.12/cs441-hw3-graphrag-assembly-0.1.0-SNAPSHOT.jar graphrag-job.jar

# Build Docker image
docker build -f Dockerfile.flink -t graphrag-flink:latest .

# Tag and push
docker tag graphrag-flink:latest $ECR_REPO:latest
docker push $ECR_REPO:latest
```

### Step 4: Create S3 Bucket and Upload Data

```bash
# Create bucket
aws s3 mb s3://graphrag-chunks --region us-east-1

# Upload chunks
aws s3 cp chunks.jsonl s3://graphrag-chunks/chunks/chunks.jsonl

# Create directories for Flink
aws s3api put-object --bucket graphrag-chunks --key checkpoints/
aws s3api put-object --bucket graphrag-chunks --key savepoints/
aws s3api put-object --bucket graphrag-chunks --key flink-ha/
```

### Step 5: Set Up IAM Role for S3 Access

```bash
# Create IAM role
aws iam create-role \
  --role-name FlinkS3AccessRole \
  --assume-role-policy-document file://deploy/trust-policy.json

# Attach S3 policy
aws iam put-role-policy \
  --role-name FlinkS3AccessRole \
  --policy-name FlinkS3Policy \
  --policy-document file://deploy/flink-s3-policy.json

# Get role ARN (save this for later)
aws iam get-role --role-name FlinkS3AccessRole --query 'Role.Arn' --output text
```

### Step 6: Configure EKS for IRSA

```bash
# Install eksctl (if not installed)
# Windows: choco install eksctl
# macOS: brew install eksctl
# Linux: https://github.com/weaveworks/eksctl

# Create OIDC provider
eksctl utils associate-iam-oidc-provider --cluster your-cluster-name --approve

# Create service account with IRSA
eksctl create iamserviceaccount \
  --cluster your-cluster-name \
  --namespace flink \
  --name flink \
  --role-name FlinkS3AccessRole \
  --attach-role-arn arn:aws:iam::YOUR_ACCOUNT_ID:role/FlinkS3AccessRole \
  --approve
```

### Step 7: Deploy Neo4j (Optional - or use Neo4j Aura)

```bash
# Apply Neo4j deployment
kubectl apply -f deploy/kubernetes/neo4j-deployment.yaml

# Or use Neo4j Aura (recommended for production)
# Update application.conf with Aura connection string
```

### Step 8: Deploy Ollama (Optional)

```bash
# Deploy Ollama DaemonSet
kubectl apply -f deploy/ollama-daemonset.yaml

# Pull model on all nodes
kubectl get pods -n flink -l app=ollama -o name | xargs -I {} kubectl exec {} -n flink -- ollama pull tinyllama
```

### Step 9: Create Kubernetes ConfigMap and Secrets

```bash
# Create namespace
kubectl create namespace flink

# Create ConfigMap
kubectl create configmap graphrag-config \
  --from-literal=neo4j.uri="neo4j+s://your-aura-instance.databases.neo4j.io" \
  --from-literal=neo44j.user="neo4j" \
  --from-literal=neo4j.database="neo4j" \
  -n flink

# Create Secret
kubectl create secret generic neo4j-credentials \
  --from-literal=password="your-neo4j-password" \
  -n flink
```

### Step 10: Deploy Flink Job

```bash
# Update job-graph-rag.yaml with:
# - Your ECR image URL
# - IAM role ARN
# - S3 bucket name

# Apply FlinkDeployment
kubectl apply -f deploy/job-graph-rag.yaml

# Check status
kubectl get flinkdeployment -n flink
kubectl get pods -n flink

# View logs
kubectl logs -n flink -l app=graphrag --tail=100 -f
```

### Step 11: Deploy REST API (Optional)

```bash
# Build API image
docker build -t graphrag-api:latest .

# Tag and push
docker tag graphrag-api:latest $ECR_REPO-api:latest
docker push $ECR_REPO-api:latest

# Update deploy/kubernetes/api-deployment.yaml with your image

# Deploy
kubectl apply -f deploy/kubernetes/api-deployment.yaml
kubectl apply -f deploy/kubernetes/api-service.yaml

# Expose service (if needed)
kubectl port-forward -n flink svc/graphrag-api 8080:8080
```

### Step 12: Monitor and Debug

```bash
# Check Flink job status
kubectl get flinkdeployment graphrag-pipeline -n flink

# View Flink Web UI
kubectl port-forward -n flink svc/graphrag-pipeline-rest 8081:8081

# Check pod logs
kubectl logs -n flink graphrag-pipeline-jobmanager-xxx

# Check Ollama status
kubectl exec -n flink <ollama-pod> -- ollama list

# Check Neo4j connection
kubectl exec -n flink <flink-pod> -- curl http://neo4j-service:7474
```

---

##  REST API Usage

### Base URL
- **Local**: `http://localhost:8080`
- **Cloud**: `http://your-api-service:8080`

### Endpoints

#### 1. Health Check
```powershell
GET /health
Invoke-RestMethod -Uri http://localhost:8080/health
```

#### 2. Metadata
```powershell
GET /v1/metadata
Invoke-RestMethod -Uri http://localhost:8080/v1/metadata
```

#### 3. Query Concepts
```powershell
POST /v1/query
$body = @{ query = "machine"; topK = 10 } | ConvertTo-Json
Invoke-RestMethod -Uri http://localhost:8080/v1/query -Method POST -Body $body -ContentType "application/json"
```

#### 4. Get Evidence
```powershell
GET /v1/evidence/:conceptId
$conceptId = "41e636ebb4669eae"
Invoke-RestMethod -Uri "http://localhost:8080/v1/evidence/$conceptId"
```

#### 5. Explore Neighbors
```powershell
GET /v1/graph/concept/:conceptId/neighbors?limit=10
Invoke-RestMethod -Uri "http://localhost:8080/v1/graph/concept/$conceptId/neighbors?limit=10"
```

#### 6. Submit Async Job
```powershell
POST /v1/jobs
$body = @{ query = "test" } | ConvertTo-Json
Invoke-RestMethod -Uri http://localhost:8080/v1/jobs -Method POST -Body $body -ContentType "application/json"
```

#### 7. Check Job Status
```powershell
GET /v1/jobs/:jobId
Invoke-RestMethod -Uri "http://localhost:8080/v1/jobs/$jobId"
```

#### 8. Explain Trace
```powershell
GET /v1/explain/trace/:requestId
Invoke-RestMethod -Uri "http://localhost:8080/v1/explain/trace/$requestId"
```

### Complete Example Workflow

```powershell
# 1. Search for concepts
$body = @{ query = "machine" } | ConvertTo-Json
$query = Invoke-RestMethod -Uri http://localhost:8080/v1/query -Method POST -Body $body -ContentType "application/json"

# 2. Get concept ID from results
$conceptId = $query.data.results[0].conceptId

# 3. Get evidence
$evidence = Invoke-RestMethod -Uri "http://localhost:8080/v1/evidence/$conceptId"

# 4. Explore neighbors
$neighbors = Invoke-RestMethod -Uri "http://localhost:8080/v1/graph/concept/$conceptId/neighbors?limit=10"
```

See [API_ENDPOINTS_QUICK_REFERENCE.md](API_ENDPOINTS_QUICK_REFERENCE.md) for detailed examples.

---

##  Configuration

### Application Configuration (`src/main/resources/application.conf`)

```hocon
# Flink settings
flink {
  parallelism = 4
  checkpoint-interval-ms = 60000
  input {
    path = "s3://graphrag-chunks/chunks/"  # or local path
    format = "jsonl"
  }
}

# Neo4j settings
neo4j {
  uri = "bolt://localhost:7687"  # or Neo4j Aura URI
  user = "neo4j"
  password = "your-password"
  database = "neo4j"
  batch-size = 200
}

# Ollama settings
ollama {
  endpoint = "http://localhost:11434"  # or "http://ollama.flink.svc.cluster.local:11434" for K8s
  model = "tinyllama:latest"
  timeout-ms = 60000
  max-retries = 3
}

# Relation extraction
relation {
  cooccur {
    window = 3
    min-pmi = 0.2
  }
  llm {
    predicate-set = ["is_a", "part_of", "causes", "synonym_of", "related_to"]
    min-confidence = 0.65
  }
}
```

### Environment Variables

You can override configuration with environment variables:

```bash
export NEO4J_URI="bolt://localhost:7687"
export NEO4J_USER="neo4j"
export NEO4J_PASS="your-password"
export OLLAMA_ENDPOINT="http://localhost:11434"
export AWS_REGION="us-east-1"
```

### Kubernetes Configuration

See `deploy/job-graph-rag.yaml` for Flink deployment configuration.

---

##  Troubleshooting

### Local Issues

#### Flink Job Fails to Start
```bash
# Check Java version
java -version  # Should be 17+

# Check Neo4j connection
nc -zv localhost 7687

# Check logs
tail -f logs/flink-*.log
```

#### Neo4j Connection Errors
```bash
# Verify Neo4j is running
docker ps | grep neo4j

# Test connection
cypher-shell -u neo4j -p your-password -a bolt://localhost:7687

# Check firewall
# Windows: Check Windows Firewall
# Linux: sudo ufw allow 7687
```

#### Ollama Not Responding
```bash
# Check Ollama is running
ollama list

# Test endpoint
curl http://localhost:11434/api/tags

# Check model is loaded
ollama pull tinyllama
```

#### API Server Won't Start
```bash
# Check port 8080 is available
netstat -an | grep 8080

# Check Neo4j connection
# API server needs Neo4j to be running

# View logs
sbt "runMain graphrag.api.ApiServer" 2>&1 | tee api-server.log
```

### Cloud/EKS Issues

#### Pods Not Starting
```bash
# Check pod status
kubectl describe pod <pod-name> -n flink

# Check events
kubectl get events -n flink --sort-by='.lastTimestamp'

# Check resource limits
kubectl top pods -n flink
```

#### S3 Access Denied
```bash
# Verify IAM role is attached
kubectl describe sa flink -n flink

# Check role ARN
aws iam get-role --role-name FlinkS3AccessRole

# Test S3 access from pod
kubectl exec -n flink <pod-name> -- aws s3 ls s3://graphrag-chunks/
```

#### Flink Job Crashes
```bash
# Check JobManager logs
kubectl logs -n flink graphrag-pipeline-jobmanager-xxx

# Check TaskManager logs
kubectl logs -n flink graphrag-pipeline-taskmanager-xxx

# Check Flink Web UI
kubectl port-forward -n flink svc/graphrag-pipeline-rest 8081:8081
# Open http://localhost:8081
```

#### Ollama Timeout in Kubernetes
```bash
# Check Ollama pods are running
kubectl get pods -n flink -l app=ollama

# Check service
kubectl get svc -n flink ollama

# Test from Flink pod
kubectl exec -n flink <flink-pod> -- wget -O- http://ollama.flink.svc.cluster.local:11434/api/tags

# Increase timeout in application.conf
ollama.timeout-ms = 60000
```

### Common Solutions

1. **Out of Memory**: Increase JVM heap size in `build.sbt` or Dockerfile
2. **Slow Processing**: Increase parallelism or TaskManager resources
3. **Neo4j Timeout**: Increase `neo4j.max-retries` and check network latency
4. **Ollama Timeout**: Increase `ollama.timeout-ms` to 60000 or higher

---

##  Project Structure

```
cs441-hw3-rag/
├── src/
│   ├── main/
│   │   ├── scala/
│   │   │   └── graphrag/
│   │   │       ├── GraphRagJob.scala          # Main Flink job
│   │   │       ├── api/
│   │   │       │   ├── ApiServer.scala        # REST API server
│   │   │       │   ├── routes/                # API endpoints
│   │   │       │   ├── services/             # Business logic
│   │   │       │   └── models/                # Data models
│   │   │       ├── extraction/                # Concept extraction
│   │   │       ├── relation/                  # Relationship discovery
│   │   │       ├── llm/                       # Ollama integration
│   │   │       └── neo4j/                     # Neo4j operations
│   │   └── resources/
│   │       └── application.conf               # Configuration
│   └── test/                                  # Unit tests
├── deploy/
│   ├── job-graph-rag.yaml                    # Flink K8s deployment
│   ├── kubernetes/                            # K8s manifests
│   ├── ollama-daemonset.yaml                  # Ollama deployment
│   └── flink-s3-policy.json                   # IAM policy
├── Dockerfile                                  # API server image
├── Dockerfile.flink                            # Flink job image
├── build.sbt                                  # Build configuration
├── README.md                                   # This file
└── API_ENDPOINTS_QUICK_REFERENCE.md           # API docs
```

---

##  Additional Documentation

- [API Endpoints Quick Reference](API_ENDPOINTS_QUICK_REFERENCE.md)
- [Complete Workflow Guide](COMPLETE_WORKFLOW.md)
- [EKS Deployment Guide](AWS_EKS_COMPLETE_DEPLOYMENT.md)
- [Neo4j Aura Setup](NEO4J_AURA_SETUP.md)

---

##  Key Concepts

### Concept Extraction
- **NER**: Named Entity Recognition using Stanford CoreNLP
- **Acronyms**: Pattern matching for acronym definitions
- **Heuristics**: Rule-based extraction for common patterns

### Relationship Discovery
- **Co-occurrence**: Concepts appearing in the same context
- **LLM Scoring**: Using Ollama to score relationship confidence
- **Relationship Types**: IS_A, SYNONYM_OF, RELATED_TO

### Provenance
- Every concept links to source text chunks
- Maintains document ID, source URI, and text spans
- Enables evidence retrieval and source attribution

---

##  Contributing

This is a homework project for CS441. For questions or issues, please refer to the course materials or contact the instructor.

---

##  License

This project is part of CS441 coursework.

---

##  Acknowledgments

- Apache Flink for stream processing
- Neo4j for graph database
- Ollama for local LLM inference
- Akka HTTP for REST API framework

---

**Last Updated**: December 2025

