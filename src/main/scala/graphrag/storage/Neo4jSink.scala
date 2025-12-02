package graphrag.storage

import graphrag.core.{Chunk, Concept, GraphWrite, ScoredRelation, UpsertEdge, UpsertNode}
import com.typesafe.config.ConfigFactory
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.sink.{RichSinkFunction, SinkFunction}
import org.neo4j.driver.{AuthTokens, Driver, GraphDatabase, Session, SessionConfig, Transaction, Values}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
 * Neo4j sink for writing graph elements to Neo4j database
 * 
 * Handles 4 types of writes:
 * 1. Chunk nodes (text spans from documents)
 * 2. Concept nodes (extracted entities)
 * 3. MENTIONS edges (Chunk -> Concept)
 * 4. Relation edges (Concept -> Concept)
 * 
 * Uses batch upserts with MERGE operations for idempotency.
 */
class Neo4jSink extends RichSinkFunction[GraphWrite] {
  
  private var driver: Driver = _
  private var session: Session = _
  private val batch = mutable.ListBuffer[GraphWrite]()
  private val batchSize: Int = ConfigFactory.load().getInt("neo4j.batch-size")
  private val maxRetries: Int = ConfigFactory.load().getInt("neo4j.max-retries")
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  override def open(parameters: Configuration): Unit = {
    val appConfig = ConfigFactory.load()
    // Prioritize environment variable for URI (useful for WSL)
    val uri = sys.env.getOrElse("NEO4J_URI", appConfig.getString("neo4j.uri"))
    val user = appConfig.getString("neo4j.user")
    val password = sys.env.getOrElse("NEO4J_PASS", appConfig.getString("neo4j.password"))
    val database = appConfig.getString("neo4j.database")
    
    logger.info(s"Connecting to Neo4j at $uri (database: $database)")
    
    driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password))
    session = driver.session(SessionConfig.forDatabase(database))
    
    // Verify connection
    Try {
      session.run("RETURN 1 as test").consume()
      logger.info(s"Neo4j connection established to database '$database'")
    } match {
      case Success(_) => // OK
      case Failure(e) =>
        logger.error(s"Failed to connect to Neo4j: ${e.getMessage}")
        throw e
    }
  }
  
  override def invoke(value: GraphWrite, context: SinkFunction.Context): Unit = {
    batch += value
    
    if (batch.length >= batchSize) {
      flushBatch()
    }
  }
  
  override def close(): Unit = {
    // Flush remaining items
    if (batch.nonEmpty) {
      flushBatch()
    }
    
    if (session != null) {
      session.close()
    }
    
    if (driver != null) {
      driver.close()
    }
    
    logger.info("Neo4j sink closed")
  }
  
  /**
   * Flush batch to Neo4j
   */
  private def flushBatch(): Unit = {
    if (batch.isEmpty) return
    
    var retries = 0
    var success = false
    
    while (retries < maxRetries && !success) {
      Try {
        val tx = session.beginTransaction()
        
        try {
          // Process all items in batch
          batch.foreach {
            case UpsertNode(label, id, props) =>
              upsertNode(tx, label, id, props)
            case UpsertEdge(fromLabel, fromId, rel, toLabel, toId, props) =>
              upsertEdge(tx, fromLabel, fromId, rel, toLabel, toId, props)
          }
          
          tx.commit()
          success = true
          logger.debug(s"Flushed ${batch.length} items to Neo4j")
        } catch {
          case e: Exception =>
            tx.rollback()
            throw e
        } finally {
          tx.close()
        }
      } match {
        case Success(_) =>
          success = true
        case Failure(e) =>
          retries += 1
          if (retries < maxRetries) {
            logger.warn(s"Neo4j batch write failed (attempt $retries/$maxRetries): ${e.getMessage}. Retrying...")
            Thread.sleep(1000 * retries) // Exponential backoff
          } else {
            logger.error(s"Neo4j batch write failed after $maxRetries attempts: ${e.getMessage}")
            throw e
          }
      }
    }
    
    batch.clear()
  }
  
  /**
   * Generic upsert for any node type
   * Handles Chunk and Concept nodes
   */
  private def upsertNode(tx: Transaction, label: String, id: String, props: Map[String, Any]): Unit = {
    // Build SET clause for all properties
    val setClause = props.keys.map(key => s"n.$key = $$$key").mkString(", ")
    
    // Determine the ID property name based on label
    val idProp = label match {
      case "Chunk" => "chunkId"
      case "Concept" => "conceptId"
      case _ => "id"
    }
    
    val cypher = s"""
      |MERGE (n:$label {$idProp: $$id})
      |SET $setClause
      |RETURN n
      |""".stripMargin
    
    // Build parameters map
    val params = new java.util.HashMap[String, AnyRef]()
    params.put("id", id)
    props.foreach { case (key, value) =>
      params.put(key, value.asInstanceOf[AnyRef])
    }
    
    tx.run(cypher, params).consume()
    logger.trace(s"Upserted $label node with id=$id")
  }
  
  /**
   * Generic upsert for any edge type
   * Handles MENTIONS (Chunk -> Concept) and typed relations (Concept -> Concept)
   * 
   * Uses MERGE for nodes to handle out-of-order arrivals in streaming context.
   * If an edge arrives before its nodes, placeholder nodes are created.
   * Later UpsertNode operations will fill in the full properties.
   */
  private def upsertEdge(
    tx: Transaction,
    fromLabel: String,
    fromId: String,
    rel: String,
    toLabel: String,
    toId: String,
    props: Map[String, Any]
  ): Unit = {
    // Determine ID property names based on labels
    val fromIdProp = fromLabel match {
      case "Chunk" => "chunkId"
      case "Concept" => "conceptId"
      case _ => "id"
    }
    
    val toIdProp = toLabel match {
      case "Chunk" => "chunkId"
      case "Concept" => "conceptId"
      case _ => "id"
    }
    
    // Neo4j relationship types must be uppercase and can't contain special chars
    val relType = rel.toUpperCase.replaceAll("[^A-Z0-9_]", "_")
    
    // Build SET clause for edge properties
    val setClause = if (props.nonEmpty) {
      "SET " + props.keys.map(key => s"r.$key = $$$key").mkString(", ")
    } else {
      ""
    }
    
    val cypher = s"""
      |MERGE (a:$fromLabel {$fromIdProp: $$fromId})
      |MERGE (b:$toLabel {$toIdProp: $$toId})
      |MERGE (a)-[r:$relType]->(b)
      |$setClause
      |SET r.updatedAt = timestamp()
      |RETURN r
      |""".stripMargin
    
    // Build parameters map
    val params = new java.util.HashMap[String, AnyRef]()
    params.put("fromId", fromId)
    params.put("toId", toId)
    props.foreach { case (key, value) =>
      params.put(key, value.asInstanceOf[AnyRef])
    }
    
    tx.run(cypher, params).consume()
    logger.trace(s"Upserted $relType edge from $fromLabel($fromId) to $toLabel($toId)")
  }
}


