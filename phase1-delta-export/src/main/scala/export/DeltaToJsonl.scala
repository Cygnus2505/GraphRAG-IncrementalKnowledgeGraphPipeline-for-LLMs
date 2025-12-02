package export

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.functions._
import org.slf4j.LoggerFactory

import java.security.MessageDigest

/**
 * Phase 1: Delta to JSONL Export Job
 * 
 * This job reads data from Delta Lake tables (created in HW2) and exports them
 * to JSONL format for consumption by the Flink streaming pipeline in Phase 2.
 * 
 * The output schema matches the Chunk case class required by Flink:
 * - chunkId: String (SHA-256 hash for content addressing)
 * - docId: String
 * - span: struct(start: Int, end: Int)
 * - text: String
 * - sourceUri: String
 * - hash: String (content hash)
 */
object DeltaToJsonl {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private val config = ConfigFactory.load()
  
  case class ExportConfig(
    deltaWarehousePath: String,
    chunksTable: String,
    docNormalizedTable: String,
    outputPath: String,
    numPartitions: Int
  )
  
  def main(args: Array[String]): Unit = {
    logger.info("Starting Phase 1: Delta to JSONL Export")
    
    // Parse command-line arguments or use config defaults
    val exportConfig = parseArgs(args)
    
    logger.info(s"Configuration: $exportConfig")
    
    // Initialize Spark session with Delta Lake support
    val spark = createSparkSession()
    
    try {
      // Execute the export
      exportDeltaToJsonl(spark, exportConfig)
      
      logger.info("Export completed successfully")
    } catch {
      case e: Exception =>
        logger.error("Export failed", e)
        System.exit(1)
    } finally {
      spark.stop()
    }
  }
  
  /**
   * Parse command-line arguments with fallback to configuration file
   */
  private def parseArgs(args: Array[String]): ExportConfig = {
    val parser = new scopt.OptionParser[ExportConfig]("DeltaToJsonl") {
      head("Delta to JSONL Export", "1.0")
      
      opt[String]("delta-path")
        .optional()
        .action((x, c) => c.copy(deltaWarehousePath = x))
        .text("Path to Delta warehouse")
      
      opt[String]("output-path")
        .optional()
        .action((x, c) => c.copy(outputPath = x))
        .text("Output path for JSONL files")
      
      opt[Int]("partitions")
        .optional()
        .action((x, c) => c.copy(numPartitions = x))
        .text("Number of output partitions")
    }
    
    val defaultConfig = ExportConfig(
      deltaWarehousePath = config.getString("delta.warehouse-path"),
      chunksTable = config.getString("delta.chunks-table"),
      docNormalizedTable = config.getString("delta.doc-normalized-table"),
      outputPath = config.getString("output.path"),
      numPartitions = config.getInt("output.num-partitions")
    )
    
    parser.parse(args, defaultConfig).getOrElse(defaultConfig)
  }
  
  /**
   * Create Spark session with Delta Lake extensions
   */
  private def createSparkSession(): SparkSession = {
    val builder = SparkSession.builder()
      .appName(config.getString("spark.app-name"))
      .master(config.getString("spark.master"))
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      // Disable Parquet schema merging for better performance
      .config("spark.sql.parquet.mergeSchema", "false")
      // Enable adaptive query execution
      .config("spark.sql.adaptive.enabled", "true")
    
    val spark = builder.getOrCreate()
    spark.sparkContext.setLogLevel(config.getString("spark.log-level"))
    
    logger.info(s"Created Spark session: ${spark.version}")
    spark
  }
  
  /**
   * Main export logic: read Delta tables, transform, and write to JSONL
   */
  private def exportDeltaToJsonl(spark: SparkSession, cfg: ExportConfig): Unit = {
    import spark.implicits._
    
    logger.info("Step 1: Reading Delta table...")
    
    // Construct full table path
    val chunksPath = s"${cfg.deltaWarehousePath}/${cfg.chunksTable}"
    
    logger.info(s"Reading chunks from: $chunksPath")
    
    // Read main chunks Delta table
    val chunksDF = readDeltaTable(spark, chunksPath, "chunks")
    val chunkCount = chunksDF.count()
    logger.info(s"Loaded $chunkCount chunks")
    
    // Try to read doc_normalized table (optional, may not exist)
    val docNormalizedDF = try {
      val docNormalizedPath = s"${cfg.deltaWarehousePath}/${cfg.docNormalizedTable}"
      logger.info(s"Attempting to read doc_normalized from: $docNormalizedPath")
      val df = readDeltaTable(spark, docNormalizedPath, "doc_normalized")
      logger.info(s"Loaded ${df.count()} normalized documents")
      df
    } catch {
      case e: Exception =>
        logger.warn(s"Could not read doc_normalized table (this is optional): ${e.getMessage}")
        // Return empty DataFrame with expected schema
        spark.emptyDataFrame
    }
    
    logger.info("Step 2: Transforming data to match Flink schema...")
    
    // Transform to match the Chunk schema expected by Flink
    val transformedDF = transformToChunkSchema(chunksDF, docNormalizedDF)
    
    val transformedCount = transformedDF.count()
    logger.info(s"Transformed $transformedCount records")
    
    if (transformedCount == 0) {
      logger.error("No records to export! Check your Delta table data.")
      throw new IllegalStateException("No data to export")
    }
    
    logger.info("Step 3: Writing to JSONL format...")
    
    // Write as JSONL (one JSON object per line)
    writeJsonl(transformedDF, cfg.outputPath, cfg.numPartitions)
    
    logger.info(s"Successfully wrote JSONL to: ${cfg.outputPath}")
    
    // Show sample output
    logger.info("Sample output (first 3 records):")
    transformedDF.show(3, truncate = false)
  }
  
  /**
   * Read a Delta table with error handling
   */
  private def readDeltaTable(spark: SparkSession, path: String, tableName: String): DataFrame = {
    try {
      spark.read.format("delta").load(path)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to read Delta table '$tableName' from path: $path", e)
        logger.info("Tip: Ensure the Delta table path is correct and contains valid Delta Lake metadata (_delta_log)")
        throw e
    }
  }
  
  /**
   * Transform the Delta tables to match the Chunk case class schema for Flink
   * 
   * Expected Flink schema:
   * case class Chunk(
   *   chunkId: String,      // SHA-256 content hash
   *   docId: String,
   *   span: (Int, Int),     // (start, end) tuple  
   *   text: String,
   *   sourceUri: String,
   *   hash: String
   * )
   * 
   * Actual HW2 schema from chunks table:
   * - chunkId, docId, chunkIx, start, end, chunkText, sectionPath, contentHash, chunkContentHash
   */
  private def transformToChunkSchema(chunks: DataFrame, docNormalized: DataFrame): DataFrame = {
    import chunks.sparkSession.implicits._
    
    // Log schemas for debugging
    logger.info("Chunks table schema:")
    chunks.printSchema()
    
    logger.info(s"Chunks table column names: ${chunks.columns.mkString(", ")}")
    
    // Check if doc_normalized table exists and has data
    val hasDocNormalized = try {
      docNormalized.count() > 0
    } catch {
      case _: Exception => false
    }
    
    if (hasDocNormalized) {
      logger.info("DocNormalized table schema:")
      docNormalized.printSchema()
    } else {
      logger.warn("DocNormalized table is empty or doesn't exist, will use chunks table only")
    }
    
    // Create span struct from existing start/end columns
    val withSpan = chunks.withColumn(
      "span",
      struct(
        col("start").as("start"),
        col("end").as("end")
      )
    )
    
    // Determine sourceUri: use sectionPath or docId
    val withSourceUri = withSpan.withColumn(
      "sourceUri",
      coalesce(
        col("sectionPath"),
        col("docId")
      )
    )
    
    // Select final columns matching Flink Chunk schema
    // Map: chunkText -> text, chunkContentHash -> hash
    val result = withSourceUri.select(
      col("chunkId"),
      col("docId"),
      col("span"),
      col("chunkText").as("text"),
      col("sourceUri"),
      col("chunkContentHash").as("hash")
    )
    
    // Validate: ensure no nulls in critical fields
    val nullCounts = result.select(
      sum(when(col("chunkId").isNull, 1).otherwise(0)).as("null_chunkId"),
      sum(when(col("docId").isNull, 1).otherwise(0)).as("null_docId"),
      sum(when(col("text").isNull, 1).otherwise(0)).as("null_text"),
      sum(when(col("hash").isNull, 1).otherwise(0)).as("null_hash")
    ).first()
    
    logger.info(s"Null counts: $nullCounts")
    
    if (nullCounts.getAs[Long](0) > 0) {
      logger.warn(s"Found ${nullCounts.getAs[Long](0)} null chunkIds!")
    }
    
    result
  }
  
  /**
   * Write DataFrame to JSONL format (one JSON object per line)
   */
  private def writeJsonl(df: DataFrame, outputPath: String, numPartitions: Int): Unit = {
    df
      .coalesce(numPartitions)  // Control output file count
      .write
      .mode(SaveMode.Overwrite)
      .json(outputPath)  // This creates JSONL format
    
    logger.info(s"JSONL files written to: $outputPath")
  }
}

