#!/bin/bash
# Upload Phase 1 chunks to S3 for Flink processing
# Prerequisites:
# - AWS CLI configured
# - S3 bucket created
# - Chunks exist in phase1-delta-export/data/

set -e

# Configuration
S3_BUCKET="${S3_BUCKET:-graphrag-chunks}"
AWS_REGION="${AWS_REGION:-us-east-1}"
PROJECT_ROOT="${PROJECT_ROOT:-$(pwd)}"
CHUNKS_DIR="${PROJECT_ROOT}/phase1-delta-export/data"

echo "=========================================="
echo "Uploading Chunks to S3"
echo "=========================================="
echo "S3 Bucket: s3://$S3_BUCKET"
echo "Region: $AWS_REGION"
echo "Source: $CHUNKS_DIR"
echo ""

# Check if AWS CLI is configured
if ! aws sts get-caller-identity &> /dev/null; then
    echo "❌ Error: AWS CLI is not configured"
    echo "   Run: aws configure"
    exit 1
fi

# Check if bucket exists
if ! aws s3 ls "s3://$S3_BUCKET" &> /dev/null; then
    echo "📦 Creating S3 bucket..."
    aws s3 mb "s3://$S3_BUCKET" --region $AWS_REGION
    
    # Enable versioning (optional)
    aws s3api put-bucket-versioning \
        --bucket $S3_BUCKET \
        --versioning-configuration Status=Enabled \
        --region $AWS_REGION
else
    echo "✅ Bucket already exists"
fi

# Check if chunks directory exists
if [ ! -d "$CHUNKS_DIR" ]; then
    echo "❌ Error: Chunks directory not found: $CHUNKS_DIR"
    echo "   Please run Phase 1 export first"
    exit 1
fi

# Upload chunks
echo "📤 Uploading chunks to S3..."
aws s3 sync "$CHUNKS_DIR" \
    "s3://$S3_BUCKET/chunks/" \
    --exclude "*" \
    --include "*.jsonl" \
    --include "*.json" \
    --region $AWS_REGION

# Verify upload
echo ""
echo "✅ Upload complete!"
echo ""
echo "Verifying upload..."
FILE_COUNT=$(aws s3 ls "s3://$S3_BUCKET/chunks/" --recursive | wc -l)
echo "   Files uploaded: $FILE_COUNT"

echo ""
echo "S3 Path for Flink job:"
echo "  s3://$S3_BUCKET/chunks/"
echo ""
echo "To use in Flink job, set:"
echo "  export S3_BUCKET=$S3_BUCKET"
echo ""



