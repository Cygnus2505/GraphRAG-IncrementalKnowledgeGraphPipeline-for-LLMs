# Stop EKS Cluster Resources
# This script provides options to stop or delete EKS resources

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  EKS Cluster Stop/Delete Options" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

Write-Host "Choose an option:" -ForegroundColor Yellow
Write-Host "1. Scale down Flink deployment (temporary - keeps cluster running)" -ForegroundColor White
Write-Host "2. Delete Flink deployment (keeps cluster running)" -ForegroundColor White
Write-Host "3. Delete all resources in namespace (keeps cluster running)" -ForegroundColor White
Write-Host "4. Delete entire EKS cluster (PERMANENT - costs money even when stopped)" -ForegroundColor Red
Write-Host "5. Scale down all deployments (temporary)" -ForegroundColor White
Write-Host ""

$choice = Read-Host "Enter option (1-5)"

$namespace = "flink"
$clusterName = Read-Host "Enter your EKS cluster name (or press Enter to skip cluster deletion)"

switch ($choice) {
    "1" {
        Write-Host "`nScaling down Flink deployment..." -ForegroundColor Green
        kubectl scale flinkdeployment graphrag-pipeline --replicas=0 -n $namespace
        Write-Host "Flink deployment scaled down. Pods will be terminated." -ForegroundColor Green
        Write-Host "To restart: kubectl scale flinkdeployment graphrag-pipeline --replicas=1 -n $namespace" -ForegroundColor Yellow
    }
    
    "2" {
        Write-Host "`nDeleting Flink deployment..." -ForegroundColor Green
        kubectl delete flinkdeployment graphrag-pipeline -n $namespace
        Write-Host "Flink deployment deleted. Cluster still running." -ForegroundColor Green
        Write-Host "To redeploy: kubectl apply -f deploy/job-graph-rag.yaml" -ForegroundColor Yellow
    }
    
    "3" {
        Write-Host "`nDeleting all resources in namespace '$namespace'..." -ForegroundColor Green
        Write-Host "This will delete:" -ForegroundColor Yellow
        Write-Host "  - Flink deployments" -ForegroundColor White
        Write-Host "  - Ollama DaemonSet" -ForegroundColor White
        Write-Host "  - API deployments" -ForegroundColor White
        Write-Host "  - Services" -ForegroundColor White
        Write-Host "  - ConfigMaps and Secrets" -ForegroundColor White
        
        $confirm = Read-Host "Are you sure? (yes/no)"
        if ($confirm -eq "yes") {
            kubectl delete all --all -n $namespace
            kubectl delete configmap --all -n $namespace
            kubectl delete secret --all -n $namespace
            Write-Host "All resources deleted from namespace '$namespace'." -ForegroundColor Green
            Write-Host "Cluster is still running (you're still paying for it)." -ForegroundColor Yellow
        } else {
            Write-Host "Cancelled." -ForegroundColor Yellow
        }
    }
    
    "4" {
        if ([string]::IsNullOrWhiteSpace($clusterName)) {
            Write-Host "Cluster name required for deletion." -ForegroundColor Red
            break
        }
        
        Write-Host "`n⚠️  WARNING: This will DELETE the entire EKS cluster!" -ForegroundColor Red
        Write-Host "This is PERMANENT and cannot be undone." -ForegroundColor Red
        Write-Host "You will still be charged for:"
        Write-Host "  - EKS control plane (~$0.10/hour)"
        Write-Host "  - EC2 nodes (if any)"
        Write-Host "  - Load balancers"
        Write-Host "  - EBS volumes"
        Write-Host ""
        Write-Host "To truly stop costs, you need to DELETE the cluster." -ForegroundColor Yellow
        Write-Host ""
        
        $confirm = Read-Host "Type 'DELETE' to confirm cluster deletion"
        if ($confirm -eq "DELETE") {
            Write-Host "`nDeleting EKS cluster: $clusterName" -ForegroundColor Red
            
            # Delete using eksctl (recommended)
            if (Get-Command eksctl -ErrorAction SilentlyContinue) {
                Write-Host "Using eksctl to delete cluster..." -ForegroundColor Green
                eksctl delete cluster --name $clusterName
            } else {
                Write-Host "eksctl not found. Using AWS CLI..." -ForegroundColor Yellow
                Write-Host "Deleting cluster nodes first..." -ForegroundColor Yellow
                
                # Delete node groups
                $nodegroups = aws eks list-nodegroups --cluster-name $clusterName --query 'nodegroups[]' --output text
                foreach ($ng in $nodegroups) {
                    Write-Host "Deleting node group: $ng" -ForegroundColor Yellow
                    aws eks delete-nodegroup --cluster-name $clusterName --nodegroup-name $ng
                }
                
                Write-Host "Waiting for node groups to be deleted (this may take 10-15 minutes)..." -ForegroundColor Yellow
                Write-Host "Then delete the cluster:" -ForegroundColor Yellow
                Write-Host "  aws eks delete-cluster --name $clusterName" -ForegroundColor White
            }
        } else {
            Write-Host "Deletion cancelled." -ForegroundColor Green
        }
    }
    
    "5" {
        Write-Host "`nScaling down all deployments..." -ForegroundColor Green
        kubectl scale deployment --all --replicas=0 -n $namespace
        kubectl scale statefulset --all --replicas=0 -n $namespace
        Write-Host "All deployments scaled down." -ForegroundColor Green
        Write-Host "To restart: kubectl scale deployment --all --replicas=1 -n $namespace" -ForegroundColor Yellow
    }
    
    default {
        Write-Host "Invalid option." -ForegroundColor Red
    }
}

Write-Host "`nDone!" -ForegroundColor Green

