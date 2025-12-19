# Neo4j Aura Configuration Guide

## Overview

This project is now configured to use **Neo4j Aura** (cloud-hosted Neo4j) instead of local Neo4j.

## Aura Credentials

```
NEO4J_URI=neo4j+s://e86ce959.databases.neo4j.io
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD="Password"
NEO4J_DATABASE=neo4j
AURA_INSTANCEID=e86ce959
AURA_INSTANCENAME=Free instance
```

## What Changed

### 1. Connection URI Format
- **Old (Local)**: `bolt://10.0.0.201:7687`
- **New (Aura)**: `neo4j+s://e86ce959.databases.neo4j.io`
- **Note**: `neo4j+s://` is the secure connection protocol for Aura

### 2. Database Name
- **Old**: `graphrag`
- **New**: `neo4j` (Aura default database)

### 3. Updated Files

#### `src/main/resources/application.conf`
```conf
neo4j {
  uri = "neo4j+s://e86ce959.databases.neo4j.io"
  user = "neo4j"
  password = "cMGY0uqeoqpi1PMEnT6zrxrXQw6Cx42iyd-ZseuODGI"
  database = "neo4j"
}
```

#### `deploy/kubernetes/configmap.yaml`
```yaml
neo4j.uri: "neo4j+s://e86ce959.databases.neo4j.io"
neo4j.user: "neo4j"
neo4j.database: "neo4j"
```

#### `deploy/kubernetes/secret-template.yaml`
```yaml
password: "cMGY0uqeoqpi1PMEnT6zrxrXQw6Cx42iyd-ZseuODGI"
```

## Testing Local Connection

### Test from Local Machine

```bash
# Set environment variables
export NEO4J_URI="neo4j+s://e86ce959.databases.neo4j.io"
export NEO4J_PASS="cMGY0uqeoqpi1PMEnT6zrxrXQw6Cx42iyd-ZseuODGI"

# Run API server locally
sbt "runMain graphrag.api.ApiServer"
```

### Test Connection with cypher-shell

```bash
# Install cypher-shell if needed
# Then connect:
cypher-shell -a neo4j+s://e86ce959.databases.neo4j.io \
  -u neo4j \
  -p cMGY0uqeoqpi1PMEnT6zrxrXQw6Cx42iyd-ZseuODGI
```

## Testing in Docker

### Run API Server in Docker

```bash
docker build -t graphrag-api:latest .

docker run -p 8080:8080 \
  -e NEO4J_URI="neo4j+s://e86ce959.databases.neo4j.io" \
  -e NEO4J_PASS="cMGY0uqeoqpi1PMEnT6zrxrXQw6Cx42iyd-ZseuODGI" \
  -e NEO4J_USER="neo4j" \
  -e NEO4J_DATABASE="neo4j" \
  graphrag-api:latest
```

## Testing in EKS

### Update ConfigMap

```bash
kubectl apply -f deploy/kubernetes/configmap.yaml
```

### Update Secret

```bash
kubectl create secret generic neo4j-credentials \
  --from-literal=password='cMGY0uqeoqpi1PMEnT6zrxrXQw6Cx42iyd-ZseuODGI' \
  --namespace=graphrag \
  --dry-run=client -o yaml | kubectl apply -f -
```

### Verify Connection

```bash
# Check API logs
kubectl logs -f deployment/graphrag-api -n graphrag

# Should see:
# "Connecting to Neo4j at neo4j+s://e86ce959.databases.neo4j.io"
# "Neo4j connection established"
```

## Flink Job Configuration

### For Flink Job Running on EKS

Update `deploy/kubernetes/flink-job.yaml` environment variables:

```yaml
env:
- name: NEO4J_URI
  value: "neo4j+s://e86ce959.databases.neo4j.io"
- name: NEO4J_PASS
  valueFrom:
    secretKeyRef:
      name: neo4j-credentials
      key: password
- name: NEO4J_DATABASE
  value: "neo4j"
```

## Important Notes

### 1. Secure Connection
- Aura uses `neo4j+s://` (secure) instead of `bolt://`
- The Neo4j Java driver automatically handles SSL/TLS

### 2. Database Name
- Aura uses `neo4j` as the default database
- If you created a custom database, update the `database` field

### 3. Network Access
- Aura is accessible from anywhere (no VPN needed)
- No need to configure firewall rules
- Works from local, Docker, and EKS

### 4. Connection Limits
- Free tier has connection limits
- Monitor usage in Aura dashboard

## Verification Steps

### 1. Test API Server Locally

```bash
# Start server
sbt "runMain graphrag.api.ApiServer"

# In another terminal, test
curl http://localhost:8080/health
curl http://localhost:8080/v1/metadata
```

### 2. Check Neo4j Connection

```bash
# View server logs - should show:
# "Connecting to Neo4j at neo4j+s://e86ce959.databases.neo4j.io"
# "Neo4j connection established"
```

### 3. Test Query Endpoint

```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{"query":"test","topK":5}'
```

## Troubleshooting

### Connection Failed

**Error**: `Unable to connect to neo4j+s://...`

**Solutions**:
1. Verify credentials are correct
2. Check Aura instance is running (Aura dashboard)
3. Ensure network connectivity (no firewall blocking)
4. Try `bolt+s://` if `neo4j+s://` doesn't work

### Authentication Failed

**Error**: `Authentication failed`

**Solutions**:
1. Double-check password (no extra spaces)
2. Verify username is `neo4j`
3. Check if password was rotated in Aura dashboard

### Database Not Found

**Error**: `Database 'graphrag' does not exist`

**Solution**: 
- Aura uses `neo4j` as default database
- Update `neo4j.database` to `neo4j` in config

## Migration Checklist

- [x] Updated `application.conf` with Aura URI
- [x] Updated Kubernetes ConfigMap
- [x] Updated Kubernetes Secret template
- [x] Verified connection string format (`neo4j+s://`)
- [x] Updated database name to `neo4j`
- [ ] Test local API server connection
- [ ] Test Docker container connection
- [ ] Test EKS deployment connection
- [ ] Verify Flink job can write to Aura

## Next Steps

1. **Test locally**: Run API server and verify connection
2. **Test in Docker**: Build and run container
3. **Deploy to EKS**: Update ConfigMap and Secret, redeploy
4. **Run Flink job**: Ensure Flink can write to Aura
5. **Monitor**: Check Aura dashboard for connections and data

## Aura Dashboard

Access your Aura instance:
- URL: https://console.neo4j.io/
- Instance ID: `e86ce959`
- Monitor connections, queries, and data

## Security Notes

⚠️ **Important**: 
- The password is now in configuration files
- For production, use Kubernetes Secrets (already configured)
- Never commit passwords to git (use `.gitignore`)
- Rotate passwords regularly in Aura dashboard



