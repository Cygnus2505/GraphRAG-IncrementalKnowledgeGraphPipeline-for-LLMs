#!/bin/bash
# Build Docker image and push to Amazon ECR
# Prerequisites:
# - AWS CLI configured
# - Docker running
# - ECR repository created

set -e

# Configuration
REGION="${AWS_REGION:-us-east-1}"
ACCOUNT_ID="${AWS_ACCOUNT_ID:-<YOUR_ACCOUNT_ID>}"
ECR_REPO_NAME="graphrag-api"
IMAGE_TAG="${IMAGE_TAG:-latest}"

ECR_REPO="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO_NAME}"
FULL_IMAGE_NAME="${ECR_REPO}:${IMAGE_TAG}"

echo "=========================================="
echo "Building and Pushing Docker Image"
echo "=========================================="
echo "ECR Repo: $ECR_REPO"
echo "Image Tag: $IMAGE_TAG"
echo ""

# Check if AWS CLI is configured
if ! aws sts get-caller-identity &> /dev/null; then
    echo "❌ Error: AWS CLI is not configured"
    echo "   Run: aws configure"
    exit 1
fi

# Login to ECR
echo "🔐 Logging in to Amazon ECR..."
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ECR_REPO

# Create ECR repository if it doesn't exist
echo "📦 Checking ECR repository..."
if ! aws ecr describe-repositories --repository-names $ECR_REPO_NAME --region $REGION &> /dev/null; then
    echo "   Creating ECR repository..."
    aws ecr create-repository \
        --repository-name $ECR_REPO_NAME \
        --region $REGION \
        --image-scanning-configuration scanOnPush=true \
        --encryption-configuration encryptionType=AES256
else
    echo "   Repository already exists"
fi

# Build Docker image
echo "🔨 Building Docker image..."
docker build -t $ECR_REPO_NAME:$IMAGE_TAG .
docker tag $ECR_REPO_NAME:$IMAGE_TAG $FULL_IMAGE_NAME

# Push to ECR
echo "📤 Pushing image to ECR..."
docker push $FULL_IMAGE_NAME

echo ""
echo "=========================================="
echo "✅ Build and Push Complete!"
echo "=========================================="
echo "Image: $FULL_IMAGE_NAME"
echo ""
echo "To deploy to EKS, run:"
echo "  export ECR_REPO=$ECR_REPO"
echo "  ./deploy/eks-deploy.sh"
echo ""






