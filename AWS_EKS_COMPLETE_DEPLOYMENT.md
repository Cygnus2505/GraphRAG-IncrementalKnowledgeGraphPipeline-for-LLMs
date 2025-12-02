# Complete AWS EKS Deployment Guide for GraphRAG

## Overview

This guide covers the complete deployment of the GraphRAG pipeline on AWS EKS according to Homework3.md requirements:

1. **Upload chunks to S3** (from Phase 1 export)
2. **Deploy Flink on EKS** (using EMR on EKS or Flink Kubernetes Operator)
3. **Run Flink pipeline** to process chunks and populate Neo4j
4. **Deploy Neo4j** (in-cluster or external)
5. **Deploy REST API** on EKS
6. **Query the API** endpoints

---

## Architecture

```
┌─────────────────┐
│  Phase 1 Chunks │
│  (JSONL files)  │
└────────┬────────┘
         │
         │ Upload
         ▼
┌─────────────────┐
│   S3 Bucket     │
│  (chunks.jsonl)  │
└────────┬────────┘
         │
         │ Read
         ▼
┌─────────────────┐      ┌──────────────┐
│  Flink on EKS   │─────▶│    Neo4j     │
│  (GraphRagJob)  │      │  (Graph DB)  │
└─────────────────┘      └──────┬───────┘
                                │
                                │ Query
                                ▼
                         ┌──────────────┐
                         │  REST API    │
                         │  (on EKS)    │
                         └──────────────┘
```

---

## Prerequisites

1. **AWS Account** with appropriate permissions
2. **AWS CLI** configured (`aws configure`)
3. **kubectl** installed
4. **eksctl** or AWS Console access to create EKS cluster
5. **Docker** installed
6. **SBT** installed (for building JAR)

### Required AWS Services:
- EKS (Elastic Kubernetes Service)
- ECR (Elastic Container Registry)
- S3 (Simple Storage Service)
- EMR on EKS (or Flink Kubernetes Operator)
- (Optional) Neo4j Aura or self-hosted Neo4j

---

## Step 1: Create EKS Cluster

### Option A: Using eksctl (Recommended)

```bash
# Install eksctl if not already installed
# macOS: brew install eksctl
# Linux: https://github.com/weaveworks/eksctl#installation

# Create EKS cluster
eksctl create cluster \
  --name graphrag-cluster \
  --region us-east-1 \
  --node-type m5.large \
  --nodes 3 \
  --nodes-min 2 \
  --nodes-max 5 \
  --managed

# Configure kubectl
aws eks update-kubeconfig --name graphrag-cluster --region us-east-1
```

### Option B: Using AWS Console

1. Go to EKS Console → Create Cluster
2. Configure cluster settings
3. Create node group
4. Update kubeconfig: `aws eks update-kubeconfig --name <cluster-name> --region <region>`

---

## Step 2: Create S3 Bucket and Upload Chunks

**⚠️ IMPORTANT**: You MUST upload the JSON/JSONL chunk files to S3 because:
- Flink runs in EKS pods (in the cloud), not on your local machine
- Flink needs to read chunks from S3 using `s3://` paths
- Your local `phase1-delta-export/data/` is not accessible from EKS

### Create S3 Bucket

```bash
# Set variables
export AWS_REGION="us-east-1"
export S3_BUCKET="graphrag-chunks-$(date +%s)"  # Unique bucket name

# Create bucket
aws s3 mb s3://$S3_BUCKET --region $AWS_REGION

# Enable versioning (optional)
aws s3api put-bucket-versioning \
  --bucket $S3_BUCKET \
  --versioning-configuration Status=Enabled
```

### Upload Chunks from Phase 1 to S3

```bash
# From your local machine
export S3_BUCKET="your-bucket-name"
export PROJECT_ROOT="/path/to/cs441-hw3-rag"

# Upload JSON/JSONL files to S3
# This is REQUIRED - Flink will read from S3, not local files!
aws s3 sync \
  $PROJECT_ROOT/phase1-delta-export/data/ \
  s3://$S3_BUCKET/chunks/ \
  --exclude "*" \
  --include "*.jsonl" \
  --include "*.json"

# Verify upload
aws s3 ls s3://$S3_BUCKET/chunks/ --recursive

# Expected output:
# chunks/part-00000.json
# chunks/chunks.jsonl
# chunks/_SUCCESS
```

**Note**: The Flink job will use this S3 path: `s3://$S3_BUCKET/chunks/`

### Create IAM Role for Flink Job

The Flink job needs access to S3. Create an IAM role with S3 read permissions:

```bash
# Create IAM policy for S3 access
cat > /tmp/flink-s3-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::${S3_BUCKET}/*",
        "arn:aws:s3:::${S3_BUCKET}"
      ]
    }
  ]
}
EOF

# Create policy
aws iam create-policy \
  --policy-name FlinkS3ReadPolicy \
  --policy-document file:///tmp/flink-s3-policy.json

# Note the Policy ARN for later use
```

---

## Step 3: Deploy Neo4j

### Option A: Neo4j Aura (Managed Service - Recommended)

1. Create Neo4j Aura instance at https://console.neo4j.io/
2. Note the connection URI (e.g., `neo4j+s://xxx.databases.neo4j.io`)
3. Update ConfigMap with Aura URI

### Option B: Neo4j in EKS (Self-hosted)

See `deploy/kubernetes/neo4j-deployment.yaml` (to be created)

### Option C: External Neo4j

Use existing Neo4j instance. Ensure it's accessible from EKS pods.

---

## Step 4: Build and Push Docker Images to ECR

**Important**: We push Docker images to **ECR (Elastic Container Registry)**, NOT ECS. Then we deploy to **EKS (Elastic Kubernetes Service)** using Kubernetes YAML manifests.

### Understanding AWS Services:
- **ECR** = Docker image registry (where images are stored)
- **EKS** = Kubernetes cluster (where we deploy using YAML)
- **ECS** = Alternative container service (NOT used in this project)

### Build Flink Job Image and Push to ECR

```bash
# Build JAR first
sbt assembly

# Set ECR variables
export AWS_ACCOUNT_ID="your-account-id"
export AWS_REGION="us-east-1"
export ECR_REPO="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

# Login to ECR (NOT ECS!)
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin $ECR_REPO

# Create ECR repository for Flink job
aws ecr create-repository --repository-name graphrag-flink-job --region $AWS_REGION || true

# Build and push Flink job image to ECR
docker build -f Dockerfile.flink -t graphrag-flink-job:latest .
docker tag graphrag-flink-job:latest $ECR_REPO/graphrag-flink-job:latest
docker push $ECR_REPO/graphrag-flink-job:latest

# Verify image in ECR
aws ecr describe-images --repository-name graphrag-flink-job --region $AWS_REGION
```

### Build and Push REST API Image to ECR

```bash
# Use existing script (pushes to ECR)
export AWS_ACCOUNT_ID="your-account-id"
export AWS_REGION="us-east-1"

chmod +x deploy/build-and-push.sh
./deploy/build-and-push.sh

# Verify image in ECR
aws ecr describe-images --repository-name graphrag-api --region $AWS_REGION
```

---

## Step 5: Deploy Flink on EKS

**How Flink Runs on EKS**:
- Flink job runs as a Kubernetes Job or Pod on EKS nodes
- Container pulls image from ECR
- Reads chunks from S3 (using `s3://` paths)
- Processes chunks and writes to Neo4j
- Job completes when done

### Option A: Using EMR on EKS (Recommended for Homework3.md)

EMR on EKS allows running Flink jobs as Kubernetes jobs with AWS management.

#### Install EMR on EKS

```bash
# Register EKS cluster with EMR
aws emr-containers create-virtual-cluster \
  --name graphrag-vc \
  --container-provider '{
    "id": "graphrag-cluster",
    "type": "EKS",
    "info": {
      "eksInfo": {
        "namespace": "emr"
      }
    }
  }' \
  --region $AWS_REGION

# Note the Virtual Cluster ID
```

#### Create EMR Job Execution Role

```bash
# Create trust policy
cat > /tmp/emr-trust-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "emr-containers.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

# Create role
aws iam create-role \
  --role-name EMRJobExecutionRole \
  --assume-role-policy-document file:///tmp/emr-trust-policy.json

# Attach policies (S3, CloudWatch, etc.)
aws iam attach-role-policy \
  --role-name EMRJobExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess

# Attach S3 policy created earlier
aws iam attach-role-policy \
  --role-name EMRJobExecutionRole \
  --policy-arn <POLICY_ARN_FROM_STEP_2>
```

#### Submit Flink Job via EMR on EKS

```bash
# Use the script: deploy/submit-flink-job-eks.sh
# (to be created)
```

### Option B: Using Flink Kubernetes Operator

```bash
# Install Flink Kubernetes Operator
kubectl create namespace flink
kubectl apply -f https://github.com/apache/flink-kubernetes-operator/releases/download/v1.0.0/flink-kubernetes-operator.yaml

# Deploy Flink Application
kubectl apply -f deploy/kubernetes/flink-application.yaml
```

---

## Step 6: Run Flink Pipeline

### Submit Flink Job

```bash
# Set variables
export S3_BUCKET="your-bucket-name"
export NEO4J_URI="bolt://your-neo4j-host:7687"
export NEO4J_PASS="your-password"
export EMR_VIRTUAL_CLUSTER_ID="your-vc-id"
export EMR_EXECUTION_ROLE_ARN="arn:aws:iam::ACCOUNT:role/EMRJobExecutionRole"

# Submit job using EMR on EKS
aws emr-containers start-job-run \
  --virtual-cluster-id $EMR_VIRTUAL_CLUSTER_ID \
  --name graphrag-pipeline \
  --execution-role-arn $EMR_EXECUTION_ROLE_ARN \
  --release-label emr-6.9.0-latest \
  --job-driver '{
    "sparkSubmitJobDriver": {
      "entryPoint": "s3://'$S3_BUCKET'/scripts/graphrag-job.jar",
      "entryPointArguments": ["--input", "s3://'$S3_BUCKET'/chunks/"],
      "sparkSubmitParameters": "--class graphrag.GraphRagJob --conf spark.executor.instances=2 --conf spark.executor.memory=2G"
    }
  }' \
  --configuration-overrides '{
    "applicationConfiguration": [
      {
        "classification": "spark-defaults",
        "properties": {
          "spark.kubernetes.container.image": "'$ECR_REPO'/graphrag-flink-job:latest",
          "neo4j.uri": "'$NEO4J_URI'",
          "neo4j.password": "'$NEO4J_PASS'"
        }
      }
    ]
  }' \
  --region $AWS_REGION
```

**Note**: For Flink (not Spark), you'll need to use Flink Kubernetes Operator or create a custom Kubernetes Job.

### Alternative: Kubernetes Job for Flink

Create a Kubernetes Job that runs the Flink job:

```bash
kubectl apply -f deploy/kubernetes/flink-job.yaml
```

---

## Step 7: Deploy REST API to EKS

**Important**: Images are already in ECR. Now we deploy to **EKS** using Kubernetes YAML manifests.

### Configure Neo4j Connection

```bash
# Update ConfigMap
kubectl apply -f deploy/kubernetes/configmap.yaml

# Create Neo4j secret
kubectl create secret generic neo4j-credentials \
  --from-literal=password='your-neo4j-password' \
  --namespace=graphrag
```

### Deploy API to EKS (using YAML manifests)

The YAML manifests in `deploy/kubernetes/` reference the ECR images:

```bash
# Set ECR repository (images are already pushed to ECR)
export ECR_REPO="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/graphrag-api"
export EKS_CLUSTER_NAME="graphrag-cluster"
export AWS_REGION="us-east-1"

# Deploy using Kubernetes YAML manifests
# The script updates YAML files with ECR image URLs and applies them to EKS
chmod +x deploy/eks-deploy.sh
./deploy/eks-deploy.sh
```

**What happens**:
1. Script reads `deploy/kubernetes/api-deployment.yaml`
2. Replaces `<YOUR_ECR_REPO>` with actual ECR URL
3. Applies YAML to EKS cluster using `kubectl apply`
4. EKS pulls images from ECR and deploys pods

---

## Step 8: Verify and Test

### Check Flink Job Status

```bash
# For EMR on EKS
aws emr-containers list-job-runs \
  --virtual-cluster-id $EMR_VIRTUAL_CLUSTER_ID \
  --region $AWS_REGION

# For Kubernetes Job
kubectl get jobs -n flink
kubectl logs -f job/graphrag-flink-job -n flink
```

### Check Neo4j Data

```bash
# Connect to Neo4j and verify
# Should see Concept and Chunk nodes, and relations
```

### Test REST API

```bash
# Get LoadBalancer URL
LB_URL=$(kubectl get service graphrag-api-service -n graphrag \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

# Test health
curl http://$LB_URL/health

# Test metadata
curl http://$LB_URL/v1/metadata

# Test query
curl -X POST http://$LB_URL/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "machine learning",
    "topK": 10
  }'
```

---

## Complete Deployment Script

Create `deploy/complete-deployment.sh` to automate all steps:

```bash
#!/bin/bash
set -e

# Configuration
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-<YOUR_ACCOUNT_ID>}"
export EKS_CLUSTER_NAME="${EKS_CLUSTER_NAME:-graphrag-cluster}"
export S3_BUCKET="${S3_BUCKET:-graphrag-chunks-$(date +%s)}"

echo "=========================================="
echo "Complete GraphRAG EKS Deployment"
echo "=========================================="

# Step 1: Create S3 bucket and upload chunks
echo "Step 1: Uploading chunks to S3..."
./deploy/upload-chunks-to-s3.sh

# Step 2: Build and push images
echo "Step 2: Building and pushing Docker images..."
./deploy/build-and-push.sh

# Step 3: Deploy Neo4j (if in-cluster)
echo "Step 3: Deploying Neo4j..."
kubectl apply -f deploy/kubernetes/neo4j-deployment.yaml || echo "Using external Neo4j"

# Step 4: Deploy Flink and submit job
echo "Step 4: Deploying Flink and submitting job..."
./deploy/submit-flink-job-eks.sh

# Step 5: Deploy REST API
echo "Step 5: Deploying REST API..."
./deploy/eks-deploy.sh

echo "=========================================="
echo "✅ Deployment Complete!"
echo "=========================================="
```

---

## Troubleshooting

### Flink Job Fails

1. Check logs:
   ```bash
   kubectl logs -f job/graphrag-flink-job -n flink
   ```

2. Verify S3 access:
   ```bash
   aws s3 ls s3://$S3_BUCKET/chunks/
   ```

3. Check Neo4j connectivity from pods

### REST API Can't Connect to Neo4j

1. Verify ConfigMap and Secret:
   ```bash
   kubectl get configmap graphrag-config -n graphrag -o yaml
   kubectl get secret neo4j-credentials -n graphrag -o yaml
   ```

2. Test connectivity:
   ```bash
   kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
     nc -zv <neo4j-host> 7687
   ```

### S3 Access Denied

1. Verify IAM role has S3 permissions
2. Check bucket policy
3. Verify EMR execution role ARN

---

## Cost Optimization

- Use Spot Instances for EKS nodes
- Use EMR Serverless for one-time jobs
- Scale down when not in use
- Use Neo4j Aura free tier for development

---

## Next Steps

1. Set up monitoring (Prometheus/Grafana)
2. Configure auto-scaling
3. Set up CI/CD pipeline
4. Add backup strategy for Neo4j
5. Implement authentication for REST API

---

## References

- [EKS User Guide](https://docs.aws.amazon.com/eks/latest/userguide/)
- [EMR on EKS](https://docs.aws.amazon.com/emr/latest/EMR-on-EKS-DevelopmentGuide/)
- [Flink Kubernetes Operator](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/)
- [Neo4j on Kubernetes](https://neo4j.com/developer/kubernetes/)

