# How to Stop Your EKS Cluster

## ⚠️ Important: EKS Cluster Costs

**EKS clusters cost money even when idle!**
- EKS control plane: ~$0.10/hour (~$73/month)
- EC2 worker nodes: Varies by instance type
- Load balancers: ~$0.0225/hour per ALB
- EBS volumes: ~$0.10/GB/month

**To truly stop costs, you must DELETE the cluster, not just stop it.**

---

## Option 1: Scale Down Resources (Temporary - Keeps Cluster Running)

This stops your workloads but keeps the cluster running (you still pay for it).

### Scale Down Flink Deployment

```powershell
# Scale down Flink
kubectl scale flinkdeployment graphrag-pipeline --replicas=0 -n flink

# Verify pods are terminated
kubectl get pods -n flink

# To restart later:
kubectl scale flinkdeployment graphrag-pipeline --replicas=1 -n flink
```

### Scale Down All Deployments

```powershell
# Scale down everything
kubectl scale deployment --all --replicas=0 -n flink
kubectl scale statefulset --all --replicas=0 -n flink
kubectl scale daemonset ollama --replicas=0 -n flink

# To restart:
kubectl scale deployment --all --replicas=1 -n flink
```

---

## Option 2: Delete Resources (Keeps Cluster Running)

This deletes your workloads but keeps the cluster running (you still pay for it).

### Delete Flink Deployment Only

```powershell
kubectl delete flinkdeployment graphrag-pipeline -n flink
```

### Delete All Resources in Namespace

```powershell
# Delete all resources
kubectl delete all --all -n flink
kubectl delete configmap --all -n flink
kubectl delete secret --all -n flink

# Delete namespace (optional)
kubectl delete namespace flink
```

---

## Option 3: Delete Entire EKS Cluster (PERMANENT - Stops All Costs)

This completely deletes the cluster and stops all costs.

### Using eksctl (Recommended)

```powershell
# Install eksctl if not installed
# Windows: choco install eksctl
# macOS: brew install eksctl

# Delete cluster (this takes 10-15 minutes)
eksctl delete cluster --name your-cluster-name

# Or with region
eksctl delete cluster --name your-cluster-name --region us-east-1
```

### Using AWS CLI

```powershell
# Set variables
$clusterName = "your-cluster-name"
$region = "us-east-1"

# 1. Delete all node groups first
$nodegroups = aws eks list-nodegroups --cluster-name $clusterName --region $region --query 'nodegroups[]' --output text
foreach ($ng in $nodegroups) {
    Write-Host "Deleting node group: $ng"
    aws eks delete-nodegroup --cluster-name $clusterName --nodegroup-name $ng --region $region
}

# 2. Wait for node groups to be deleted (check status)
aws eks describe-nodegroup --cluster-name $clusterName --nodegroup-name $ng --region $region

# 3. Delete the cluster
aws eks delete-cluster --name $clusterName --region $region

# 4. Verify deletion
aws eks list-clusters --region $region
```

### Using AWS Console

1. Go to **Amazon EKS** in AWS Console
2. Select your cluster
3. Click **Delete**
4. Confirm deletion
5. Wait 10-15 minutes for deletion to complete

---

## Option 4: Use the Stop Script

I've created a PowerShell script for easy stopping:

```powershell
.\deploy\stop-eks-cluster.ps1
```

This script provides an interactive menu to:
- Scale down deployments
- Delete resources
- Delete the entire cluster

---

## Quick Reference Commands

### Check What's Running

```powershell
# List all resources
kubectl get all -n flink

# List Flink deployments
kubectl get flinkdeployment -n flink

# List pods
kubectl get pods -n flink

# Check cluster status
aws eks describe-cluster --name your-cluster-name --region us-east-1
```

### Stop Everything (Temporary)

```powershell
# Scale down Flink
kubectl scale flinkdeployment graphrag-pipeline --replicas=0 -n flink

# Scale down API (if deployed)
kubectl scale deployment graphrag-api --replicas=0 -n flink

# Scale down Ollama
kubectl scale daemonset ollama --replicas=0 -n flink
```

### Delete Everything (Permanent)

```powershell
# Delete Flink
kubectl delete flinkdeployment graphrag-pipeline -n flink

# Delete all resources
kubectl delete all --all -n flink

# Delete cluster (using eksctl)
eksctl delete cluster --name your-cluster-name
```

---

## Cost Comparison

| Action | Control Plane Cost | Node Cost | Total Monthly |
|--------|-------------------|-----------|---------------|
| **Scale Down** | $73/month | $0 (if nodes deleted) | ~$73/month |
| **Delete Resources** | $73/month | $0 (if nodes deleted) | ~$73/month |
| **Delete Cluster** | $0 | $0 | **$0** |

**Recommendation**: If you're not using the cluster, **DELETE it** to avoid charges.

---

## Restarting After Stopping

### If You Scaled Down

```powershell
# Restart Flink
kubectl scale flinkdeployment graphrag-pipeline --replicas=1 -n flink

# Restart API
kubectl scale deployment graphrag-api --replicas=1 -n flink
```

### If You Deleted Resources

```powershell
# Redeploy everything
kubectl apply -f deploy/job-graph-rag.yaml
kubectl apply -f deploy/ollama-daemonset.yaml
kubectl apply -f deploy/kubernetes/
```

### If You Deleted the Cluster

You'll need to recreate it from scratch. See [AWS_EKS_COMPLETE_DEPLOYMENT.md](AWS_EKS_COMPLETE_DEPLOYMENT.md) for setup instructions.

---

## Troubleshooting

### Cluster Won't Delete

```powershell
# Check for remaining resources
kubectl get all --all-namespaces

# Check for load balancers
aws elbv2 describe-load-balancers --region us-east-1

# Check for persistent volumes
kubectl get pv

# Force delete stuck resources
kubectl delete --all --force --grace-period=0 -n flink
```

### Nodes Won't Terminate

```powershell
# Check node status
kubectl get nodes

# Drain nodes before deletion
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data

# Delete node
kubectl delete node <node-name>
```

---

## Summary

- **To temporarily stop**: Scale down deployments (still pay for cluster)
- **To stop costs**: Delete the entire cluster
- **To restart**: Scale up or redeploy resources

**Remember**: EKS clusters cost money even when idle. Delete the cluster if you're not using it!

