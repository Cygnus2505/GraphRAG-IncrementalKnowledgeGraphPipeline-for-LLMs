# Check Ollama Status on EKS
# This script checks if Ollama is running and responding

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Checking Ollama Status on EKS" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# 1. Check Ollama Pods
Write-Host "1. Checking Ollama Pods..." -ForegroundColor Yellow
kubectl get pods -n flink -l app=ollama
Write-Host ""

# 2. Check Ollama Service
Write-Host "2. Checking Ollama Service..." -ForegroundColor Yellow
kubectl get svc ollama -n flink
Write-Host ""

# 3. Check Service Endpoints
Write-Host "3. Checking Service Endpoints..." -ForegroundColor Yellow
kubectl get endpoints ollama -n flink
Write-Host ""

# 4. Check Pod Status Details
Write-Host "4. Checking Pod Status..." -ForegroundColor Yellow
$pods = kubectl get pods -n flink -l app=ollama -o json | ConvertFrom-Json
foreach ($pod in $pods.items) {
    $podName = $pod.metadata.name
    $status = $pod.status.phase
    $ready = $pod.status.containerStatuses[0].ready
    Write-Host "  Pod: $podName" -ForegroundColor Cyan
    Write-Host "    Status: $status"
    Write-Host "    Ready: $ready"
    Write-Host ""
}

# 5. Test Connectivity from Flink Pod
Write-Host "5. Testing Connectivity from Flink Pod..." -ForegroundColor Yellow
$flinkPod = kubectl get pods -n flink -l app=graphrag -o jsonpath='{.items[0].metadata.name}' 2>$null
if ($flinkPod) {
    Write-Host "  Testing from pod: $flinkPod" -ForegroundColor Cyan
    Write-Host "  Endpoint: http://ollama.flink.svc.cluster.local:11434/api/tags"
    
    # Try wget
    $result = kubectl exec $flinkPod -n flink -- sh -c "wget -qO- --timeout=5 http://ollama.flink.svc.cluster.local:11434/api/tags 2>&1" 2>&1
    if ($result -and $result.Length -gt 0) {
        Write-Host "  ✅ Ollama is responding!" -ForegroundColor Green
        Write-Host "  Response: $($result.Substring(0, [Math]::Min(200, $result.Length)))"
    } else {
        Write-Host "  ⚠️  No response or empty response" -ForegroundColor Yellow
    }
} else {
    Write-Host "  ⚠️  No Flink pod found to test from" -ForegroundColor Yellow
}
Write-Host ""

# 6. Check Ollama Logs
Write-Host "6. Recent Ollama Logs (last 10 lines)..." -ForegroundColor Yellow
$ollamaPod = kubectl get pods -n flink -l app=ollama -o jsonpath='{.items[0].metadata.name}' 2>$null
if ($ollamaPod) {
    kubectl logs $ollamaPod -n flink --tail=10
} else {
    Write-Host "  No Ollama pods found" -ForegroundColor Red
}
Write-Host ""

# 7. Port Forward Test (if needed)
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Manual Testing Commands" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "To test Ollama connectivity:" -ForegroundColor Yellow
Write-Host "  1. Port forward (in separate terminal):" -ForegroundColor Cyan
Write-Host "     kubectl port-forward -n flink svc/ollama 11434:11434" -ForegroundColor White
Write-Host ""
Write-Host "  2. Then test locally:" -ForegroundColor Cyan
Write-Host "     curl http://localhost:11434/api/tags" -ForegroundColor White
Write-Host ""
Write-Host "  3. Or test from Flink pod:" -ForegroundColor Cyan
Write-Host "     kubectl exec <flink-pod> -n flink -- wget -qO- http://ollama.flink.svc.cluster.local:11434/api/tags" -ForegroundColor White
Write-Host ""

