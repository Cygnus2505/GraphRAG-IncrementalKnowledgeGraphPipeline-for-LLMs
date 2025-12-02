package export

import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

/**
 * Utility to verify Delta table structure and contents
 * Run this first to ensure your HW2 Delta tables are readable
 */
object VerifyDeltaTables {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  def main(args: Array[String]): Unit = {
    val warehousePath = if (args.length > 0) args(0) 
                       else "C:/Users/harsh/IdeaProjects/Cs441-hw2-rag/out/delta-tables/warehouse/rag.db"
    
    logger.info(s"Verifying Delta tables at: $warehousePath")
    
    val spark = SparkSession.builder()
      .appName("VerifyDeltaTables")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .getOrCreate()
    
    spark.sparkContext.setLogLevel("WARN")
    
    try {
      // Try to list what's in the warehouse directory
      logger.info("\n=== Checking warehouse directory structure ===")
      val fs = org.apache.hadoop.fs.FileSystem.get(spark.sparkContext.hadoopConfiguration)
      val path = new org.apache.hadoop.fs.Path(warehousePath)
      
      if (fs.exists(path)) {
        logger.info(s"✓ Warehouse path exists: $warehousePath")
        
        val tables = fs.listStatus(path).map(_.getPath.getName).filter(!_.startsWith("."))
        logger.info(s"Found ${tables.length} potential tables:")
        tables.foreach(t => logger.info(s"  - $t"))
        
        // Try to read each table
        tables.foreach { tableName =>
          verifyTable(spark, s"$warehousePath/$tableName", tableName)
        }
      } else {
        logger.error(s"✗ Warehouse path does not exist: $warehousePath")
        logger.info("Please verify the path to your HW2 Delta tables")
      }
      
    } catch {
      case e: Exception =>
        logger.error("Error verifying tables", e)
    } finally {
      spark.stop()
    }
  }
  
  private def verifyTable(spark: SparkSession, tablePath: String, tableName: String): Unit = {
    logger.info(s"\n=== Verifying table: $tableName ===")
    logger.info(s"Path: $tablePath")
    
    try {
      // Check if _delta_log exists
      val fs = org.apache.hadoop.fs.FileSystem.get(spark.sparkContext.hadoopConfiguration)
      val deltaLogPath = new org.apache.hadoop.fs.Path(s"$tablePath/_delta_log")
      
      if (!fs.exists(deltaLogPath)) {
        logger.warn(s"✗ No _delta_log found at: $deltaLogPath")
        logger.warn(s"  This may not be a valid Delta table")
        return
      }
      
      logger.info("✓ _delta_log directory exists")
      
      // Try to read the table
      val df = spark.read.format("delta").load(tablePath)
      val count = df.count()
      
      logger.info(s"✓ Successfully read table")
      logger.info(s"  Row count: $count")
      logger.info(s"  Schema:")
      df.printSchema()
      
      if (count > 0) {
        logger.info(s"  Sample data (first 3 rows):")
        df.show(3, truncate = 60)
        
        // Show column names and types
        logger.info(s"  Columns: ${df.columns.mkString(", ")}")
      } else {
        logger.warn("  Table is empty!")
      }
      
    } catch {
      case e: Exception =>
        logger.error(s"✗ Failed to read table: ${e.getMessage}")
        e.printStackTrace()
    }
  }
}






























