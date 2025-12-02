# Phase 1: Delta to JSONL Export

This project exports data from Delta Lake tables (created in Homework 2) to JSONL format for consumption by the Flink pipeline (Phase 2).

## Project Structure

```
phase1-delta-export/
├── build.sbt                           # SBT build configuration
├── src/
│   └── main/
│       ├── scala/export/
│       │   ├── DeltaToJsonl.scala      # Main export job
│       │   └── VerifyDeltaTables.scala # Verification utility
│       └── resources/
│           └── application.conf         # Configuration
└── README.md
```

## Prerequisites

- Scala 2.12.18
- SBT 1.9.7+
- Delta Lake tables from Homework 2 at the configured path
- Java 11 or higher

## Step 1: Verify Your Delta Tables

Before running the export, verify that your Delta tables are readable:

```bash
cd phase1-delta-export
sbt "runMain export.VerifyDeltaTables"
```

Or with a custom path:

```bash
sbt "runMain export.VerifyDeltaTables C:/path/to/your/warehouse"
```

**Expected Output:**
- ✓ Warehouse path exists
- List of tables found (e.g., `chunks`, `doc_normalized`)
- Schema and sample data for each table

## Step 2: Configure the Export

Edit `src/main/resources/application.conf` to set your paths:

```hocon
delta {
  warehouse-path = "C:/Users/harsh/IdeaProjects/Cs441-hw2-rag/out/delta-tables/warehouse/rag.db"
  chunks-table = "chunks"
  doc-normalized-table = "doc_normalized"
}

output {
  path = "./data/chunks.jsonl"
  num-partitions = 1
}
```

## Step 3: Run the Export

```bash
sbt "runMain export.DeltaToJsonl"
```

Or with command-line arguments:

```bash
sbt "runMain export.DeltaToJsonl --delta-path C:/your/path --output-path ./output/chunks.jsonl"
```

**Command-line options:**
- `--delta-path <path>` - Path to Delta warehouse
- `--output-path <path>` - Output path for JSONL files
- `--partitions <n>` - Number of output partitions (default: 1)

## Output Schema

The export produces JSONL files with the following schema (matching Flink's Chunk case class):

```json
{
  "chunkId": "sha256_hash_of_content",
  "docId": "document_identifier",
  "span": {
    "start": 0,
    "end": 500
  },
  "text": "chunk text content...",
  "sourceUri": "path/to/source/file.pdf",
  "hash": "content_hash"
}
```

## Troubleshooting

### "Warehouse path does not exist"
- Verify the path in `application.conf` is correct
- Ensure you're using forward slashes or escaped backslashes: `C:/path` or `C:\\path`

### "No _delta_log found"
- The directory may not be a valid Delta table
- Check if tables are actually in subdirectories (e.g., `warehouse/rag.db/chunks`)

### "Failed to read table"
- Ensure Delta Lake dependencies are correct in `build.sbt`
- Check that the table was created with a compatible Delta Lake version

### Schema mismatch
- Run `VerifyDeltaTables` to see actual column names
- Update `DeltaToJsonl.scala` transform logic if your HW2 schema differs

## Testing the Output

After export, verify the JSONL format:

```bash
# Windows PowerShell
Get-Content data/chunks.jsonl -First 3

# Or view in any text editor
```

Each line should be a valid JSON object.

## Next Steps

Once you have successfully exported JSONL files:
1. Note the output path (e.g., `./data/chunks.jsonl`)
2. This path will be the input for Phase 2 (Flink pipeline)
3. Proceed to Phase 2 setup






























