# Setup and Deploy Flink Job to EKS
# Prerequisites:
# - EKS cluster created and kubectl configured
# - Docker image pushed to ECR
# - S3 bucket with chunks uploaded
# - Neo4j accessible

# Configuration
$NAMESPACE = if ($env:FLINK_NAMESPACE) { $env:FLINK_NAMESPACE } else { "flink" }
$S3_BUCKET = if ($env:S3_BUCKET) { $env:S3_BUCKET } else { "graphrag-chunks" }
$AWS_ACCOUNT_ID = if ($env:AWS_ACCOUNT_ID) { $env:AWS_ACCOUNT_ID } else { "405721655991" }
$AWS_REGION = if ($env:AWS_REGION) { $env:AWS_REGION } else { "us-east-1" }
$ECR_REPO = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/graphrag-flink"
$IMAGE_TAG = if ($env:IMAGE_TAG) { $env:IMAGE_TAG } else { "latest" }

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Setting up Flink Job on EKS" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Namespace: $NAMESPACE"
Write-Host "S3 Bucket: s3://$S3_BUCKET"
Write-Host "ECR Image: $ECR_REPO`:$IMAGE_TAG"
Write-Host ""

# Check if kubectl is configured
kubectl cluster-info 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Error: kubectl is not configured or cluster is not accessible" -ForegroundColor Red
    Write-Host "   Run: aws eks update-kubeconfig --name <cluster-name> --region $AWS_REGION" -ForegroundColor Yellow
    exit 1
}

# Create namespace if it doesn't exist
$nsExists = kubectl get namespace $NAMESPACE 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "📦 Creating namespace: $NAMESPACE" -ForegroundColor Yellow
    kubectl create namespace $NAMESPACE
} else {
    Write-Host "✅ Namespace already exists: $NAMESPACE" -ForegroundColor Green
}

# Create ServiceAccount (IRSA will be added separately)
Write-Host "👤 Creating ServiceAccount..." -ForegroundColor Yellow
$saYaml = @"
apiVersion: v1
kind: ServiceAccount
metadata:
  name: flink
  namespace: $NAMESPACE
"@
$saYaml | kubectl apply -f -

# Create ConfigMap
Write-Host "⚙️  Creating ConfigMap..." -ForegroundColor Yellow
$neo4jUri = if ($env:NEO4J_URI) { $env:NEO4J_URI } else { "neo4j+s://e86ce959.databases.neo4j.io" }
$neo4jUser = if ($env:NEO4J_USER) { $env:NEO4J_USER } else { "neo4j" }
$neo4jDb = if ($env:NEO4J_DATABASE) { $env:NEO4J_DATABASE } else { "neo4j" }

kubectl create configmap graphrag-config `
    --from-literal=neo4j.uri="$neo4jUri" `
    --from-literal=neo4j.user="$neo4jUser" `
    --from-literal=neo4j.database="$neo4jDb" `
    -n $NAMESPACE `
    --dry-run=client -o yaml | kubectl apply -f -

# Create Neo4j secret
Write-Host "🔐 Creating Neo4j secret..." -ForegroundColor Yellow
if (-not $env:NEO4J_PASS) {
    Write-Host "❌ Error: NEO4J_PASS environment variable not set" -ForegroundColor Red
    Write-Host "   Set it with: `$env:NEO4J_PASS = 'your-password'" -ForegroundColor Yellow
    exit 1
}

kubectl create secret generic neo4j-credentials `
    --from-literal=password="$env:NEO4J_PASS" `
    -n $NAMESPACE `
    --dry-run=client -o yaml | kubectl apply -f -

# Update and apply Flink job YAML
Write-Host "🐳 Preparing Flink job manifest..." -ForegroundColor Yellow
$jobYaml = Get-Content "deploy/kubernetes/flink-job.yaml" -Raw
$jobYaml = $jobYaml -replace '<YOUR_ECR_REPO>', $ECR_REPO
$jobYaml = $jobYaml -replace '<YOUR_S3_BUCKET>', $S3_BUCKET
$jobYaml = $jobYaml -replace ':latest', ":$IMAGE_TAG"

$jobYaml | kubectl apply -f -

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "✅ Flink Job Deployed!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Job status:" -ForegroundColor Cyan
kubectl get job graphrag-flink-job -n $NAMESPACE

Write-Host ""
Write-Host "To view logs:" -ForegroundColor Yellow
Write-Host "  kubectl logs -f job/graphrag-flink-job -n $NAMESPACE" -ForegroundColor Cyan
Write-Host ""
Write-Host "To check job status:" -ForegroundColor Yellow
Write-Host "  kubectl get job graphrag-flink-job -n $NAMESPACE" -ForegroundColor Cyan
Write-Host ""
Write-Host "To check pods:" -ForegroundColor Yellow
Write-Host "  kubectl get pods -n $NAMESPACE -l app=graphrag-flink" -ForegroundColor Cyan
Write-Host ""


