# Build Docker image and push to Amazon ECR
# Prerequisites:
# - AWS CLI configured
# - Docker running
# - ECR repository created

# Configuration
$REGION = if ($env:AWS_REGION) { $env:AWS_REGION } else { "us-east-1" }
$ACCOUNT_ID = if ($env:AWS_ACCOUNT_ID) { $env:AWS_ACCOUNT_ID } else { "405721655991" }
$ECR_REPO_NAME = "graphrag-api"
$IMAGE_TAG = if ($env:IMAGE_TAG) { $env:IMAGE_TAG } else { "latest" }

$ECR_REPO = "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/$ECR_REPO_NAME"
$FULL_IMAGE_NAME = "${ECR_REPO}:${IMAGE_TAG}"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Building and Pushing Docker Image" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "ECR Repo: $ECR_REPO"
Write-Host "Image Tag: $IMAGE_TAG"
Write-Host ""

# Check if AWS CLI is configured
aws sts get-caller-identity 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Error: AWS CLI is not configured" -ForegroundColor Red
    Write-Host "   Run: aws configure" -ForegroundColor Yellow
    exit 1
}

# Login to ECR
Write-Host "🔐 Logging in to Amazon ECR..." -ForegroundColor Yellow
$loginPassword = aws ecr get-login-password --region $REGION
$loginPassword | docker login --username AWS --password-stdin $ECR_REPO

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Failed to login to ECR" -ForegroundColor Red
    exit 1
}

# Create ECR repository if it doesn't exist
Write-Host "📦 Checking ECR repository..." -ForegroundColor Yellow
$repoCheck = aws ecr describe-repositories --repository-names $ECR_REPO_NAME --region $REGION 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "   Creating ECR repository..." -ForegroundColor Yellow
    aws ecr create-repository `
        --repository-name $ECR_REPO_NAME `
        --region $REGION `
        --image-scanning-configuration scanOnPush=true `
        --encryption-configuration encryptionType=AES256
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "   ✅ Repository created" -ForegroundColor Green
    } else {
        Write-Host "   ❌ Failed to create repository" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "   ✅ Repository already exists" -ForegroundColor Green
}

# Check if local image exists, if not build it
$localImageName = "${ECR_REPO_NAME}:${IMAGE_TAG}"
$imageCheck = docker images $localImageName --format "{{.Repository}}:{{.Tag}}" 2>&1
$imageExists = ($imageCheck -ne "" -and $LASTEXITCODE -eq 0)

if (-not $imageExists) {
    Write-Host "🔨 Building Docker image..." -ForegroundColor Yellow
    docker build -t $localImageName .
    
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Build failed" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "✅ Using existing local image: $localImageName" -ForegroundColor Green
}

# Tag for ECR
Write-Host "🏷️  Tagging image for ECR..." -ForegroundColor Yellow
docker tag $localImageName $FULL_IMAGE_NAME

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Failed to tag image" -ForegroundColor Red
    exit 1
}

# Push to ECR
Write-Host "📤 Pushing image to ECR..." -ForegroundColor Yellow
docker push $FULL_IMAGE_NAME

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Push failed" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "✅ Build and Push Complete!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host "Image: $FULL_IMAGE_NAME" -ForegroundColor Cyan
Write-Host ""
Write-Host "To deploy to EKS, use this image:" -ForegroundColor Yellow
Write-Host "  $FULL_IMAGE_NAME" -ForegroundColor Cyan
Write-Host ""

