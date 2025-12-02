#!/bin/bash
# Submit Flink GraphRAG job to EKS
# Prerequisites:
# - EKS cluster configured
# - kubectl configured
# - Docker image built and pushed to ECR
# - S3 bucket with chunks uploaded
# - Neo4j accessible

set -e

# Configuration
NAMESPACE="${FLINK_NAMESPACE:-flink}"
S3_BUCKET="${S3_BUCKET:-graphrag-chunks}"
ECR_REPO="${ECR_REPO:-<YOUR_ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/graphrag-flink-job}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

echo "=========================================="
echo "Submitting Flink Job to EKS"
echo "=========================================="
echo "Namespace: $NAMESPACE"
echo "S3 Bucket: s3://$S3_BUCKET"
echo "Image: $ECR_REPO:$IMAGE_TAG"
echo ""

# Check if kubectl is configured
if ! kubectl cluster-info &> /dev/null; then
    echo "❌ Error: kubectl is not configured or cluster is not accessible"
    exit 1
fi

# Create namespace if it doesn't exist
if ! kubectl get namespace $NAMESPACE &> /dev/null; then
    echo "📦 Creating namespace..."
    kubectl create namespace $NAMESPACE
fi

# Create ServiceAccount for Flink (with IRSA for S3 access)
echo "👤 Creating ServiceAccount..."
kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: flink
  namespace: $NAMESPACE
  annotations:
    eks.amazonaws.com/role-arn: ${FLINK_IAM_ROLE_ARN:-""}
EOF

# Create ConfigMap if it doesn't exist in flink namespace
if ! kubectl get configmap graphrag-config -n $NAMESPACE &> /dev/null; then
    echo "⚙️  Creating ConfigMap in flink namespace..."
    kubectl create configmap graphrag-config \
        --from-literal=neo4j.uri="${NEO4J_URI:-neo4j+s://e86ce959.databases.neo4j.io}" \
        --from-literal=neo4j.user="${NEO4J_USER:-neo4j}" \
        --from-literal=neo4j.database="${NEO4J_DATABASE:-neo4j}" \
        -n $NAMESPACE
fi

# Create Neo4j secret if it doesn't exist
if ! kubectl get secret neo4j-credentials -n $NAMESPACE &> /dev/null; then
    echo "🔐 Creating Neo4j secret..."
    if [ -z "$NEO4J_PASS" ]; then
        echo "   Error: NEO4J_PASS environment variable not set"
        exit 1
    fi
    kubectl create secret generic neo4j-credentials \
        --from-literal=password="$NEO4J_PASS" \
        -n $NAMESPACE
fi

# Update flink-job.yaml with actual values
echo "🐳 Preparing Flink job manifest..."
sed "s|<YOUR_ECR_REPO>|$ECR_REPO|g" deploy/kubernetes/flink-job.yaml | \
    sed "s|<YOUR_S3_BUCKET>|$S3_BUCKET|g" | \
    sed "s|:latest|:$IMAGE_TAG|g" | \
    kubectl apply -f -

# Wait for job to start
echo "⏳ Waiting for job to start..."
kubectl wait --for=condition=Ready pod -l app=graphrag-flink -n $NAMESPACE --timeout=60s || true

# Show job status
echo ""
echo "=========================================="
echo "✅ Job Submitted!"
echo "=========================================="
echo ""
echo "Job status:"
kubectl get job graphrag-flink-job -n $NAMESPACE

echo ""
echo "To view logs:"
echo "  kubectl logs -f job/graphrag-flink-job -n $NAMESPACE"
echo ""
echo "To check job status:"
echo "  kubectl get job graphrag-flink-job -n $NAMESPACE"
echo ""

