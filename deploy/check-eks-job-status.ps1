# Check EKS job status and CloudWatch logs (PowerShell version)
# This script checks both kubectl job status and AWS CloudWatch logs

param(
    [string]$Namespace = "flink",
    [string]$JobName = "graphrag-flink-job",
    [string]$AwsRegion = "us-east-1",
    [string]$EksClusterName = "graphrag-cluster"
)

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Checking EKS Job Status" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Job Name: $JobName"
Write-Host "Namespace: $Namespace"
Write-Host "Cluster: $EksClusterName"
Write-Host "Region: $AwsRegion"
Write-Host ""

# Check if kubectl is available
try {
    $null = kubectl cluster-info 2>&1
} catch {
    Write-Host "❌ Error: kubectl is not configured or cluster is not accessible" -ForegroundColor Red
    exit 1
}

# Check if AWS CLI is available
try {
    $null = aws sts get-caller-identity 2>&1
} catch {
    Write-Host "❌ Error: AWS CLI is not configured" -ForegroundColor Red
    exit 1
}

# 1. Check Job Status via kubectl
Write-Host "📊 Checking job status via kubectl..." -ForegroundColor Yellow
Write-Host ""

$jobExists = kubectl get job $JobName -n $Namespace 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "⚠️  Job '$JobName' not found in namespace '$Namespace'" -ForegroundColor Yellow
    Write-Host "Available jobs:" -ForegroundColor Yellow
    kubectl get jobs -n $Namespace
    exit 1
}

kubectl get job $JobName -n $Namespace

Write-Host ""
Write-Host "Job Details:" -ForegroundColor Yellow
kubectl describe job $JobName -n $Namespace | Select-String -Pattern "Name:|Namespace:|Status:|Completions:|Duration:|Events:"

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Checking Pod Status" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$pods = kubectl get pods -n $Namespace -l app=graphrag-flink -o json | ConvertFrom-Json

if ($pods.items.Count -eq 0) {
    Write-Host "⚠️  No pods found for job $JobName" -ForegroundColor Yellow
} else {
    Write-Host "Pods:" -ForegroundColor Yellow
    kubectl get pods -n $Namespace -l app=graphrag-flink
    Write-Host ""
    
    foreach ($pod in $pods.items) {
        $podName = $pod.metadata.name
        $podStatus = $pod.status.phase
        $startTime = $pod.status.startTime
        
        Write-Host "Pod: $podName" -ForegroundColor Cyan
        Write-Host "  Status: $podStatus"
        
        if ($podStatus -eq "Running") {
            Write-Host "  ✅ Pod is RUNNING" -ForegroundColor Green
        } elseif ($podStatus -eq "Succeeded") {
            Write-Host "  ✅ Pod COMPLETED successfully" -ForegroundColor Green
        } elseif ($podStatus -eq "Failed") {
            Write-Host "  ❌ Pod FAILED" -ForegroundColor Red
        } elseif ($podStatus -eq "Pending") {
            Write-Host "  ⏳ Pod is PENDING" -ForegroundColor Yellow
        } else {
            Write-Host "  ⚠️  Pod status: $podStatus" -ForegroundColor Yellow
        }
        
        Write-Host "  Start Time: $startTime"
        Write-Host ""
    }
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Recent Pod Logs (last 20 lines)" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

if ($pods.items.Count -gt 0) {
    foreach ($pod in $pods.items) {
        $podName = $pod.metadata.name
        Write-Host "--- Logs for pod: $podName ---" -ForegroundColor Cyan
        $logs = kubectl logs $podName -n $Namespace --tail=20 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host $logs
        } else {
            Write-Host "  (No logs available yet)" -ForegroundColor Yellow
        }
        Write-Host ""
    }
} else {
    Write-Host "No pods available to fetch logs" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Checking CloudWatch Logs" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Try to find CloudWatch log groups
$logGroups = @(
    "/aws/eks/$EksClusterName/cluster",
    "/aws/containerinsights/$EksClusterName/application",
    "/aws/eks/$EksClusterName/application"
)

$foundLogs = $false

foreach ($logGroup in $logGroups) {
    try {
        $logGroupExists = aws logs describe-log-groups `
            --log-group-name-prefix $logGroup `
            --region $AwsRegion `
            --query 'logGroups[0].logGroupName' `
            --output text 2>&1
        
        if ($logGroupExists -and $logGroupExists -ne "None") {
            Write-Host "✅ Found log group: $logGroup" -ForegroundColor Green
            $foundLogs = $true
            
            # Get recent log streams
            Write-Host "Recent log streams (last 5):" -ForegroundColor Yellow
            aws logs describe-log-streams `
                --log-group-name $logGroup `
                --order-by LastEventTime `
                --descending `
                --max-items 5 `
                --region $AwsRegion `
                --query 'logStreams[*].[logStreamName,lastEventTime]' `
                --output table 2>&1
            
            # Try to get recent log events if we have a pod name
            if ($pods.items.Count -gt 0) {
                $podName = $pods.items[0].metadata.name
                Write-Host ""
                Write-Host "Searching for logs containing pod: $podName" -ForegroundColor Yellow
                
                # Get logs from last hour
                $startTime = [int64]((Get-Date).AddHours(-1).ToUniversalTime() - (Get-Date "1970-01-01")).TotalMilliseconds
                $endTime = [int64]((Get-Date).ToUniversalTime() - (Get-Date "1970-01-01")).TotalMilliseconds
                
                $logEvents = aws logs filter-log-events `
                    --log-group-name $logGroup `
                    --start-time $startTime `
                    --end-time $endTime `
                    --filter-pattern $podName `
                    --region $AwsRegion `
                    --max-items 10 `
                    --query 'events[*].[timestamp,message]' `
                    --output table 2>&1
                
                if ($LASTEXITCODE -eq 0 -and $logEvents) {
                    Write-Host $logEvents
                } else {
                    Write-Host "  (No matching logs found in last hour)" -ForegroundColor Yellow
                }
            }
            Write-Host ""
        }
    } catch {
        # Log group doesn't exist, continue
    }
}

if (-not $foundLogs) {
    Write-Host "⚠️  No CloudWatch log groups found for cluster '$EksClusterName'" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "To enable CloudWatch logging for EKS:" -ForegroundColor Yellow
    Write-Host "  1. Enable control plane logging:" -ForegroundColor Yellow
    Write-Host "     aws eks update-cluster-config --name $EksClusterName --logging '{\"enable\":[\"api\",\"audit\",\"authenticator\",\"controllerManager\",\"scheduler\"]}'" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  2. Or install Container Insights:" -ForegroundColor Yellow
    Write-Host "     kubectl apply -f https://raw.githubusercontent.com/aws-samples/amazon-cloudwatch-container-insights/latest/k8s-deployment-manifest-templates/deployment-mode/daemonset/container-insights-monitoring/quickstart/cwagent-fluentd-quickstart.yaml" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Quick Commands" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "View live logs:" -ForegroundColor Yellow
Write-Host "  kubectl logs -f job/$JobName -n $Namespace" -ForegroundColor Cyan
Write-Host ""
Write-Host "Check job status:" -ForegroundColor Yellow
Write-Host "  kubectl get job $JobName -n $Namespace" -ForegroundColor Cyan
Write-Host ""
Write-Host "Check pods:" -ForegroundColor Yellow
Write-Host "  kubectl get pods -n $Namespace -l app=graphrag-flink" -ForegroundColor Cyan
Write-Host ""
Write-Host "Describe job:" -ForegroundColor Yellow
Write-Host "  kubectl describe job $JobName -n $Namespace" -ForegroundColor Cyan
Write-Host ""

