#!/bin/bash
# Complete GraphRAG deployment to AWS EKS
# This script automates the entire deployment process

set -e

# Configuration - UPDATE THESE VALUES
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-<YOUR_ACCOUNT_ID>}"
export EKS_CLUSTER_NAME="${EKS_CLUSTER_NAME:-graphrag-cluster}"
export S3_BUCKET="${S3_BUCKET:-graphrag-chunks-$(date +%s)}"
export NEO4J_URI="${NEO4J_URI:-neo4j+s://e86ce959.databases.neo4j.io}"
export NEO4J_PASS="${NEO4J_PASS:-cMGY0uqeoqpi1PMEnT6zrxrXQw6Cx42iyd-ZseuODGI}"
export NEO4J_DATABASE="${NEO4J_DATABASE:-neo4j}"
export ECR_REPO="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

echo "=========================================="
echo "Complete GraphRAG EKS Deployment"
echo "=========================================="
echo "Region: $AWS_REGION"
echo "Cluster: $EKS_CLUSTER_NAME"
echo "S3 Bucket: $S3_BUCKET"
echo ""

# Check prerequisites
echo "🔍 Checking prerequisites..."

if ! aws sts get-caller-identity &> /dev/null; then
    echo "❌ Error: AWS CLI is not configured"
    echo "   Run: aws configure"
    exit 1
fi

if ! kubectl cluster-info &> /dev/null; then
    echo "❌ Error: kubectl is not configured"
    echo "   Run: aws eks update-kubeconfig --name $EKS_CLUSTER_NAME --region $AWS_REGION"
    exit 1
fi

if [ "$AWS_ACCOUNT_ID" = "<YOUR_ACCOUNT_ID>" ]; then
    echo "❌ Error: Please set AWS_ACCOUNT_ID environment variable"
    exit 1
fi

echo "✅ Prerequisites check passed"
echo ""

# Step 1: Upload chunks to S3
echo "=========================================="
echo "Step 1: Uploading chunks to S3"
echo "=========================================="
export S3_BUCKET=$S3_BUCKET
chmod +x deploy/upload-chunks-to-s3.sh
./deploy/upload-chunks-to-s3.sh
echo ""

# Step 2: Build and push Docker images
echo "=========================================="
echo "Step 2: Building and pushing Docker images"
echo "=========================================="

# Build Flink job image
echo "Building Flink job image..."
docker build -f Dockerfile.flink -t graphrag-flink-job:latest .

# Login to ECR
aws ecr get-login-password --region $AWS_REGION | \
    docker login --username AWS --password-stdin $ECR_REPO

# Create ECR repositories
echo "Creating ECR repositories..."
aws ecr create-repository --repository-name graphrag-flink-job --region $AWS_REGION 2>/dev/null || true
aws ecr create-repository --repository-name graphrag-api --region $AWS_REGION 2>/dev/null || true

# Tag and push Flink job image
docker tag graphrag-flink-job:latest $ECR_REPO/graphrag-flink-job:latest
docker push $ECR_REPO/graphrag-flink-job:latest

# Build and push REST API image
echo "Building REST API image..."
export AWS_ACCOUNT_ID=$AWS_ACCOUNT_ID
export AWS_REGION=$AWS_REGION
chmod +x deploy/build-and-push.sh
./deploy/build-and-push.sh
echo ""

# Step 3: Neo4j Aura (external - no deployment needed)
echo "=========================================="
echo "Step 3: Using Neo4j Aura (external)"
echo "=========================================="
echo "Using Neo4j Aura: $NEO4J_URI"
echo "No in-cluster deployment needed - Aura is cloud-hosted"
echo ""

# Step 4: Configure Neo4j connection
echo "=========================================="
echo "Step 4: Configuring Neo4j connection"
echo "=========================================="
kubectl apply -f deploy/kubernetes/namespace.yaml
kubectl create configmap graphrag-config \
    --from-literal=neo4j.uri="$NEO4J_URI" \
    --from-literal=neo4j.user="${NEO4J_USER:-neo4j}" \
    --from-literal=neo4j.database="${NEO4J_DATABASE:-graphrag}" \
    -n graphrag --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic neo4j-credentials \
    --from-literal=password="$NEO4J_PASS" \
    -n graphrag --dry-run=client -o yaml | kubectl apply -f -
echo ""

# Step 5: Submit Flink job
echo "=========================================="
echo "Step 5: Submitting Flink job"
echo "=========================================="
export S3_BUCKET=$S3_BUCKET
export ECR_REPO=$ECR_REPO/graphrag-flink-job
export NEO4J_URI=$NEO4J_URI
export NEO4J_PASS=$NEO4J_PASS
chmod +x deploy/submit-flink-job-eks.sh
./deploy/submit-flink-job-eks.sh
echo ""

# Wait for Flink job to complete (optional)
echo "Waiting for Flink job to start..."
sleep 10
kubectl get job graphrag-flink-job -n flink || kubectl get job graphrag-flink-job -n graphrag || true
echo ""
read -p "Press Enter when Flink job has completed (check logs with: kubectl logs -f job/graphrag-flink-job -n flink)..."

# Step 6: Deploy REST API
echo "=========================================="
echo "Step 6: Deploying REST API"
echo "=========================================="
export ECR_REPO=$ECR_REPO/graphrag-api
export EKS_CLUSTER_NAME=$EKS_CLUSTER_NAME
export AWS_REGION=$AWS_REGION
chmod +x deploy/eks-deploy.sh
./deploy/eks-deploy.sh
echo ""

# Step 7: Get service endpoint
echo "=========================================="
echo "✅ Deployment Complete!"
echo "=========================================="
echo ""
echo "Service endpoints:"
kubectl get service graphrag-api-service -n graphrag

echo ""
LB_URL=$(kubectl get service graphrag-api-service -n graphrag \
    -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending...")

if [ -n "$LB_URL" ] && [ "$LB_URL" != "pending..." ]; then
    echo "REST API URL: http://$LB_URL"
    echo ""
    echo "Test the API:"
    echo "  curl http://$LB_URL/health"
    echo "  curl http://$LB_URL/v1/metadata"
    echo ""
    echo "Query example:"
    echo "  curl -X POST http://$LB_URL/v1/query \\"
    echo "    -H 'Content-Type: application/json' \\"
    echo "    -d '{\"query\":\"machine learning\",\"topK\":10}'"
else
    echo "LoadBalancer is being created. Check status with:"
    echo "  kubectl get service graphrag-api-service -n graphrag"
fi

echo ""
echo "To view logs:"
echo "  kubectl logs -f deployment/graphrag-api -n graphrag"
echo ""

