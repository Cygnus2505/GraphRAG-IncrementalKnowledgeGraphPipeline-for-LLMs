#!/bin/bash
# Deploy GraphRAG REST API to Amazon EKS
# Prerequisites:
# - AWS CLI configured
# - kubectl configured for your EKS cluster
# - Docker image built and pushed to ECR
# - EKS cluster created and accessible

set -e

# Configuration
NAMESPACE="graphrag"
CLUSTER_NAME="${EKS_CLUSTER_NAME:-graphrag-cluster}"
REGION="${AWS_REGION:-us-east-1}"
ECR_REPO="${ECR_REPO:-<YOUR_ACCOUNT_ID>.dkr.ecr.${REGION}.amazonaws.com/graphrag-api}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

echo "=========================================="
echo "Deploying GraphRAG REST API to EKS"
echo "=========================================="
echo "Cluster: $CLUSTER_NAME"
echo "Region: $REGION"
echo "ECR Repo: $ECR_REPO"
echo "Image Tag: $IMAGE_TAG"
echo ""

# Check if kubectl is configured
if ! kubectl cluster-info &> /dev/null; then
    echo "❌ Error: kubectl is not configured or cluster is not accessible"
    echo "   Run: aws eks update-kubeconfig --name $CLUSTER_NAME --region $REGION"
    exit 1
fi

# Create namespace
echo "📦 Creating namespace..."
kubectl apply -f deploy/kubernetes/namespace.yaml

# Create ConfigMap
echo "⚙️  Creating ConfigMap..."
kubectl apply -f deploy/kubernetes/configmap.yaml

# Create Secret (if not exists)
if ! kubectl get secret neo4j-credentials -n $NAMESPACE &> /dev/null; then
    echo "🔐 Creating Neo4j credentials secret..."
    echo "   Please update deploy/kubernetes/secret-template.yaml with your Neo4j password"
    echo "   Then run: kubectl apply -f deploy/kubernetes/secret-template.yaml"
    read -p "   Press Enter to continue after updating the secret..."
    kubectl apply -f deploy/kubernetes/secret-template.yaml
else
    echo "✅ Secret already exists"
fi

# Update deployment with ECR image
echo "🐳 Updating deployment with ECR image..."
sed "s|<YOUR_ECR_REPO>|$ECR_REPO|g" deploy/kubernetes/api-deployment.yaml | \
    sed "s|:latest|:$IMAGE_TAG|g" | \
    kubectl apply -f -

# Create Service
echo "🌐 Creating Service..."
kubectl apply -f deploy/kubernetes/api-service.yaml

# Wait for deployment
echo "⏳ Waiting for deployment to be ready..."
kubectl wait --for=condition=available --timeout=300s deployment/graphrag-api -n $NAMESPACE

# Get service endpoint
echo ""
echo "=========================================="
echo "✅ Deployment Complete!"
echo "=========================================="
echo ""
echo "Service endpoints:"
kubectl get service graphrag-api-service -n $NAMESPACE

echo ""
echo "To get the LoadBalancer URL:"
echo "  kubectl get service graphrag-api-service -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'"
echo ""
echo "To check pod status:"
echo "  kubectl get pods -n $NAMESPACE"
echo ""
echo "To view logs:"
echo "  kubectl logs -f deployment/graphrag-api -n $NAMESPACE"
echo ""






