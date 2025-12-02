# Upload Phase 1 chunks to S3 for Flink processing
# Prerequisites:
# - AWS CLI configured
# - S3 bucket created
# - Chunks exist in phase1-delta-export/data/

# Configuration
$S3_BUCKET = if ($env:S3_BUCKET) { $env:S3_BUCKET } else { "graphrag-chunks" }
$AWS_REGION = if ($env:AWS_REGION) { $env:AWS_REGION } else { "us-east-1" }
$PROJECT_ROOT = if ($env:PROJECT_ROOT) { $env:PROJECT_ROOT } else { (Get-Location).Path }
$CHUNKS_DIR = Join-Path $PROJECT_ROOT "phase1-delta-export\data"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Uploading Chunks to S3" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "S3 Bucket: s3://$S3_BUCKET"
Write-Host "Region: $AWS_REGION"
Write-Host "Source: $CHUNKS_DIR"
Write-Host ""

# Check if AWS CLI is configured
aws sts get-caller-identity 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Error: AWS CLI is not configured" -ForegroundColor Red
    Write-Host "   Run: aws configure" -ForegroundColor Yellow
    exit 1
}

# Check if bucket exists
$bucketCheck = aws s3 ls "s3://$S3_BUCKET" 2>&1
$bucketExists = ($LASTEXITCODE -eq 0)

if (-not $bucketExists) {
    Write-Host "📦 Creating S3 bucket..." -ForegroundColor Yellow
    aws s3 mb "s3://$S3_BUCKET" --region $AWS_REGION
    
    if ($LASTEXITCODE -eq 0) {
        # Enable versioning (optional)
        aws s3api put-bucket-versioning --bucket $S3_BUCKET --versioning-configuration Status=Enabled --region $AWS_REGION
        Write-Host "✅ Bucket created successfully" -ForegroundColor Green
    } else {
        Write-Host "❌ Failed to create bucket" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "✅ Bucket already exists" -ForegroundColor Green
}

# Check if chunks directory exists
if (-not (Test-Path $CHUNKS_DIR)) {
    Write-Host "❌ Error: Chunks directory not found: $CHUNKS_DIR" -ForegroundColor Red
    Write-Host "   Please run Phase 1 export first" -ForegroundColor Yellow
    exit 1
}

# Upload chunks
Write-Host "📤 Uploading chunks to S3..." -ForegroundColor Yellow
aws s3 sync "$CHUNKS_DIR" "s3://$S3_BUCKET/chunks/" --exclude "*" --include "*.jsonl" --include "*.json" --region $AWS_REGION

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Upload failed" -ForegroundColor Red
    exit 1
}

# Verify upload
Write-Host ""
Write-Host "✅ Upload complete!" -ForegroundColor Green
Write-Host ""
Write-Host "Verifying upload..." -ForegroundColor Cyan
$files = aws s3 ls "s3://$S3_BUCKET/chunks/" --recursive
$fileCount = ($files | Measure-Object -Line).Lines
Write-Host "   Files uploaded: $fileCount" -ForegroundColor Green

Write-Host ""
Write-Host "S3 Path for Flink job:" -ForegroundColor Cyan
Write-Host "  s3://$S3_BUCKET/chunks/" -ForegroundColor Yellow
Write-Host ""
Write-Host "To use in Flink job, set:" -ForegroundColor Cyan
Write-Host "  `$env:S3_BUCKET = `"$S3_BUCKET`"" -ForegroundColor Yellow
Write-Host ""
