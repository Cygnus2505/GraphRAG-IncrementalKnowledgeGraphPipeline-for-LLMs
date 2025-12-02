package graphrag.api.services

import graphrag.api.models._
import org.neo4j.driver.{AuthTokens, Driver, GraphDatabase, Session, SessionConfig}
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * Neo4j query service for REST API
 * Provides methods to query the knowledge graph
 */
class Neo4jQueryService {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private val appConfig = ConfigFactory.load()
  
  // Neo4j connection
  private val uri = sys.env.getOrElse("NEO4J_URI", appConfig.getString("neo4j.uri"))
  private val user = appConfig.getString("neo4j.user")
  private val password = sys.env.getOrElse("NEO4J_PASS", appConfig.getString("neo4j.password"))
  private val database = appConfig.getString("neo4j.database")
  
  private var driver: Driver = _
  
  /**
   * Initialize Neo4j connection
   */
  def connect(): Unit = {
    logger.info(s"Connecting to Neo4j at $uri (database: $database)")
    driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password))
    
    // Verify connection
    Try {
      val session = driver.session(SessionConfig.forDatabase(database))
      try {
        session.run("RETURN 1 as test").consume()
        logger.info("Neo4j connection established")
      } finally {
        session.close()
      }
    } match {
      case Success(_) => // OK
      case Failure(e) =>
        logger.error(s"Failed to connect to Neo4j: ${e.getMessage}")
        throw e
    }
  }
  
  /**
   * Close Neo4j connection
   */
  def close(): Unit = {
    if (driver != null) {
      driver.close()
      logger.info("Neo4j connection closed")
    }
  }
  
  /**
   * Get a Neo4j session
   */
  private def getSession: Session = {
    driver.session(SessionConfig.forDatabase(database))
  }
  
  // ============================================================================
  // MetadataService Queries
  // ============================================================================
  
  /**
   * Get graph metadata (node counts, edge counts, etc.)
   */
  def getGraphMetadata: Try[GraphMetadata] = Try {
    val session = getSession
    try {
      // Count all nodes
      val nodeCountResult = session.run("MATCH (n) RETURN count(n) as count")
      val nodeCount = nodeCountResult.single().get("count").asLong()
      
      // Count all relationships
      val edgeCountResult = session.run("MATCH ()-[r]->() RETURN count(r) as count")
      val edgeCount = edgeCountResult.single().get("count").asLong()
      
      // Count Concept nodes
      val conceptCountResult = session.run("MATCH (c:Concept) RETURN count(c) as count")
      val conceptCount = conceptCountResult.single().get("count").asLong()
      
      // Count Chunk nodes
      val chunkCountResult = session.run("MATCH (ch:Chunk) RETURN count(ch) as count")
      val chunkCount = chunkCountResult.single().get("count").asLong()
      
      // Count relationship types
      val relationTypesResult = session.run(
        "MATCH ()-[r]->() RETURN type(r) as relType, count(r) as count ORDER BY count DESC"
      )
      
      val relationTypes = relationTypesResult.asScala.map { record =>
        val relType = record.get("relType").asString()
        val count = record.get("count").asLong()
        relType -> count
      }.toMap
      
      GraphMetadata(
        nodeCount = nodeCount,
        edgeCount = edgeCount,
        conceptCount = conceptCount,
        chunkCount = chunkCount,
        relationTypes = relationTypes,
        lastUpdated = Some(java.time.Instant.now().toString)
      )
    } finally {
      session.close()
    }
  }
  
  // ============================================================================
  // EvidenceService Queries
  // ============================================================================
  
  /**
   * Get all chunks that mention a concept (provenance)
   */
  def getChunksMentioningConcept(conceptId: String): Try[EvidenceResponse] = Try {
    val session = getSession
    try {
      // Get concept info
      val params = new java.util.HashMap[String, AnyRef]()
      params.put("conceptId", conceptId)
      
      val conceptResult = session.run(
        """
          |MATCH (c:Concept {conceptId: $conceptId})
          |RETURN c.conceptId as conceptId, c.lemma as lemma, c.surface as surface
          |""".stripMargin,
        params
      )
      
      if (!conceptResult.hasNext) {
        throw new NoSuchElementException(s"Concept not found: $conceptId")
      }
      
      val conceptRecord = conceptResult.single()
      val lemma = conceptRecord.get("lemma").asString()
      val surface = conceptRecord.get("surface").asString()
      
      // Get all chunks mentioning this concept
      val chunksParams = new java.util.HashMap[String, AnyRef]()
      chunksParams.put("conceptId", conceptId)
      
      val chunksResult = session.run(
        """
          |MATCH (ch:Chunk)-[:MENTIONS]->(c:Concept {conceptId: $conceptId})
          |RETURN ch.chunkId as chunkId, 
          |       ch.docId as docId, 
          |       ch.text as text, 
          |       ch.sourceUri as sourceUri,
          |       ch.spanStart as spanStart,
          |       ch.spanEnd as spanEnd
          |LIMIT 100
          |""".stripMargin,
        chunksParams
      )
      
      // IMPORTANT: Materialize all results BEFORE closing the session
      val chunksList = chunksResult.list().asScala.toList
      val chunks = chunksList.map { record =>
        ChunkEvidence(
          chunkId = record.get("chunkId").asString(),
          docId = record.get("docId").asString(),
          text = record.get("text").asString(),
          sourceUri = record.get("sourceUri").asString(),
          span = SpanInfo(
            start = record.get("spanStart").asInt(),
            end = record.get("spanEnd").asInt()
          )
        )
      }
      
      EvidenceResponse(
        conceptId = conceptId,
        lemma = lemma,
        surface = surface,
        chunks = chunks,
        totalMentions = chunks.length
      )
    } finally {
      session.close()
    }
  }
  
  // ============================================================================
  // ExploreService Queries
  // ============================================================================
  
  /**
   * Get neighboring concepts and their relations
   */
  def getConceptNeighbors(conceptId: String, limit: Int = 20): Try[ExploreResponse] = Try {
    val session = getSession
    try {
      // Get center concept
      val centerParams = new java.util.HashMap[String, AnyRef]()
      centerParams.put("conceptId", conceptId)
      
      val centerResult = session.run(
        """
          |MATCH (c:Concept {conceptId: $conceptId})
          |RETURN c.conceptId as conceptId, c.lemma as lemma, c.surface as surface, c.origin as origin
          |""".stripMargin,
        centerParams
      )
      
      if (!centerResult.hasNext) {
        throw new NoSuchElementException(s"Concept not found: $conceptId")
      }
      
      val centerRecord = centerResult.single()
      val center = ConceptNode(
        conceptId = centerRecord.get("conceptId").asString(),
        lemma = centerRecord.get("lemma").asString(),
        surface = centerRecord.get("surface").asString(),
        origin = centerRecord.get("origin").asString()
      )
      
      // Get neighbors (both incoming and outgoing relations)
      val neighborParams = new java.util.HashMap[String, AnyRef]()
      neighborParams.put("conceptId", conceptId)
      neighborParams.put("limit", Int.box(limit))
      
      val neighborsResult = session.run(
        """
          |MATCH (c:Concept {conceptId: $conceptId})-[r]-(neighbor:Concept)
          |RETURN DISTINCT neighbor.conceptId as conceptId, 
          |       neighbor.lemma as lemma, 
          |       neighbor.surface as surface,
          |       neighbor.origin as origin
          |LIMIT $limit
          |""".stripMargin,
        neighborParams
      )
      
      // IMPORTANT: Materialize all results BEFORE closing the session
      val neighborsList = neighborsResult.list().asScala.toList
      val neighbors = neighborsList.map { record =>
        ConceptNode(
          conceptId = record.get("conceptId").asString(),
          lemma = record.get("lemma").asString(),
          surface = record.get("surface").asString(),
          origin = record.get("origin").asString()
        )
      }
      
      // Get relations
      val relationParams = new java.util.HashMap[String, AnyRef]()
      relationParams.put("conceptId", conceptId)
      relationParams.put("limit", Int.box(limit))
      
      val relationsResult = session.run(
        """
          |MATCH (c:Concept {conceptId: $conceptId})-[r]-(neighbor:Concept)
          |RETURN c.conceptId as fromId, 
          |       neighbor.conceptId as toId, 
          |       type(r) as relType,
          |       r.confidence as confidence,
          |       r.evidence as evidence
          |LIMIT $limit
          |""".stripMargin,
        relationParams
      )
      
      // IMPORTANT: Materialize all results BEFORE closing the session
      val relationsList = relationsResult.list().asScala.toList
      val relations = relationsList.map { record =>
        ConceptRelation(
          fromConceptId = record.get("fromId").asString(),
          toConceptId = record.get("toId").asString(),
          relationType = record.get("relType").asString(),
          confidence = if (record.get("confidence").isNull) None else Some(record.get("confidence").asDouble()),
          evidence = if (record.get("evidence").isNull) None else Some(record.get("evidence").asString())
        )
      }
      
      ExploreResponse(
        center = center,
        neighbors = neighbors,
        relations = relations,
        totalNeighbors = neighbors.length
      )
    } finally {
      session.close()
    }
  }
  
  // ============================================================================
  // QueryService Queries
  // ============================================================================
  
  /**
   * Search concepts by lemma pattern (simple query)
   */
  def searchConcepts(pattern: String, limit: Int = 20): Try[Seq[QueryResult]] = Try {
    val session = getSession
    try {
      val params = new java.util.HashMap[String, AnyRef]()
      params.put("pattern", pattern)
      params.put("limit", Int.box(limit))
      
      val result = session.run(
        """
          |MATCH (c:Concept)
          |WHERE c.lemma CONTAINS $pattern OR c.surface CONTAINS $pattern
          |OPTIONAL MATCH (c)-[r]-(related:Concept)
          |WITH c, count(r) as relCount, collect(DISTINCT related.lemma) as relatedLemmas
          |RETURN c.conceptId as conceptId, 
          |       c.lemma as lemma, 
          |       relatedLemmas,
          |       relCount as score
          |ORDER BY score DESC
          |LIMIT $limit
          |""".stripMargin,
        params
      )
      
      // IMPORTANT: Materialize all results BEFORE closing the session
      // Convert to list first to ensure all records are consumed while session is open
      val records = result.list().asScala.toList
      
      // Now process the materialized records
      records.flatMap { record =>
        try {
          // Handle conceptId and lemma (required fields) - check first
          val conceptIdOpt = try {
            val idField = record.get("conceptId")
            if (idField == null || idField.isNull) {
              None
            } else {
              Some(idField.asString())
            }
          } catch {
            case _: Exception => None
          }
          
          val lemmaOpt = try {
            val lemmaField = record.get("lemma")
            if (lemmaField == null || lemmaField.isNull) {
              None
            } else {
              Some(lemmaField.asString())
            }
          } catch {
            case _: Exception => None
          }
          
          // If either required field is missing, skip this record
          if (conceptIdOpt.isEmpty || lemmaOpt.isEmpty) {
            logger.warn(s"Skipping record with missing conceptId or lemma")
            None
          } else {
            // Handle relatedLemmas (may be null or empty)
            // Note: collect() in Cypher can return nulls in the list
            val relatedLemmasList = record.get("relatedLemmas")
            val relatedLemmas = if (relatedLemmasList == null || relatedLemmasList.isNull) {
              Seq.empty[String]
            } else {
              try {
                // asList() returns List[Object] - items may be Value, String, or null
                val list = relatedLemmasList.asList()
                if (list == null) {
                  Seq.empty[String]
                } else {
                  list.asScala.flatMap { item =>
                    try {
                      if (item == null) {
                        None
                      } else {
                        val str = item match {
                          case v: org.neo4j.driver.Value => 
                            if (!v.isNull) v.asString() else null
                          case s: String => s
                          case _ => item.toString
                        }
                        if (str != null && str.nonEmpty) Some(str) else None
                      }
                    } catch {
                      case _: Exception => None
                    }
                  }.toSeq
                }
              } catch {
                case e: Exception => 
                  logger.warn(s"Error extracting relatedLemmas: ${e.getMessage}")
                  Seq.empty[String]
              }
            }
            
            // Handle score (may be null)
            val scoreValue = try {
              val scoreField = record.get("score")
              if (scoreField == null || scoreField.isNull) {
                0.0
              } else {
                scoreField.asInt().toDouble
              }
            } catch {
              case _: Exception => 0.0
            }
            
            Some(QueryResult(
              conceptId = conceptIdOpt.get,
              lemma = lemmaOpt.get,
              relatedConcepts = relatedLemmas,
              score = scoreValue
            ))
          }
        } catch {
          case e: Exception =>
            // Log error but don't fail entire query - skip this record
            logger.warn(s"Error processing query result record: ${e.getMessage}")
            None
        }
      }
    } finally {
      session.close()
    }
  }
}

