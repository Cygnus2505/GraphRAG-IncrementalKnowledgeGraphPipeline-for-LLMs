 package graphrag

import graphrag.core.{Chunk, Concept, GraphWrite, Mentions, RelationCandidate, ScoredRelation}
import graphrag.ingestion.{ConceptExtractor, JsonDeser}
import graphrag.llm.{OllamaClient, RelationScorer}
import graphrag.relation.CoOccurExtractor
import graphrag.storage.Neo4jSink
import org.apache.flink.api.common.eventtime.WatermarkStrategy
import org.apache.flink.api.common.functions.{RichFlatMapFunction, RichMapFunction}
import org.apache.flink.configuration.Configuration
import org.apache.flink.connector.file.src.FileSource
import org.apache.flink.connector.file.src.reader.TextLineInputFormat
import org.apache.flink.core.fs.Path
import org.apache.flink.streaming.api.scala._
import org.apache.flink.util.Collector
import org.slf4j.LoggerFactory
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * Phase 2+3: Flink GraphRAG Streaming Job
 * 
 * This job:
 * 1. Ingests JSONL chunks from Phase 1
 * 2. Extracts concepts using Stanford CoreNLP
 * 3. Finds co-occurrences of concepts within chunks
 * 4. Scores relations using Ollama LLM
 * 5. Writes concepts and relations to Neo4j
 * 
 * Usage:
 *   sbt "runMain graphrag.GraphRagJob"
 *   sbt "runMain graphrag.GraphRagJob --input path/to/chunks.jsonl"
 */
object GraphRagJob {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private val appConfig = ConfigFactory.load()
  
  def main(args: Array[String]): Unit = {
    logger.info("Starting GraphRAG Flink Job - Phase 2+3 (Concept Extraction + Relation Scoring)")
    
    // Parse command-line arguments
    val inputPath = if (args.length > 0 && args(0) == "--input") {
      args(1)
    } else {
      // Default to Phase 1 output
      "phase1-delta-export/data/chunks.jsonl"
    }
    
    logger.info(s"Input path: $inputPath")
    
    // Check if Ollama is available (optional, will skip LLM scoring if not)
    val ollamaAvailable = OllamaClient.isAvailable()
    if (ollamaAvailable) {
      logger.info("Ollama LLM is available - relation scoring will be enabled")
    } else {
      logger.warn("Ollama LLM is not available - relation scoring will be skipped")
    }
    
    // Set up Flink execution environment
    val env = createExecutionEnvironment()
    
    // Step 1: Ingest JSONL chunks
    val rawChunks: DataStream[String] = ingestJsonl(env, inputPath)
    
    // Step 2: Parse JSON lines into Chunk objects
    val chunks: DataStream[Chunk] = rawChunks
      .flatMap(new ParseJsonFlatMapFunction)
      .name("parse-json")
      .uid("parse-json")
    
    // Step 3: Extract concepts from each chunk
    // Output: (Chunk, Mentions) pairs to keep chunk text for evidence
    val chunkWithMentions: DataStream[(Chunk, Mentions)] = chunks
      .flatMap(new ExtractConceptsWithChunkFlatMapFunction)
      .name("extract-concepts")
      .uid("extract-concepts")
    
    // Step 4: Extract co-occurrences and create relation candidates within chunks
    // We process all mentions for a chunk together to find pairs
    val relationCandidates: DataStream[RelationCandidate] = chunks
      .flatMap(new ExtractConceptsAndCreateCandidatesFlatMapFunction)
      .name("create-candidates")
      .uid("create-candidates")
    
    // Step 5: Score relations using LLM (async)
    val scoredRelations: DataStream[ScoredRelation] =
      if (ollamaAvailable) {
        relationCandidates
          .flatMap(new ScoreRelationFlatMapFunction)
          .name("score-relations")
          .uid("score-relations")
      } else {
        // If Ollama not available, create empty stream
        // Note: fromCollection is a source, so we can't call .name() on it directly
        env.fromCollection(Seq.empty[ScoredRelation])
      }
    
    // Step 7: Convert to GraphWrite operations for Neo4j
    
    // 7a. Write Chunk nodes
    val chunkWrites: DataStream[GraphWrite] = chunks
      .map(new ChunkToGraphWriteMapFunction)
      .name("map-chunks-to-writes")
      .uid("map-chunks-to-writes")
    
    // 7b. Write Concept nodes
    val conceptWrites: DataStream[GraphWrite] = chunkWithMentions
      .map(new ExtractConceptFromTupleMapFunction)
      .map(new ConceptToGraphWriteMapFunction)
      .name("map-concepts-to-writes")
      .uid("map-concepts-to-writes")
    
    // 7c. Write MENTIONS edges (Chunk -> Concept)
    val mentionsWrites: DataStream[GraphWrite] = chunkWithMentions
      .map(new MentionsToGraphWriteMapFunction)
      .name("map-mentions-to-writes")
      .uid("map-mentions-to-writes")
    
    // 7d. Write Relation edges (Concept -> Concept)
    val relationWrites: DataStream[GraphWrite] = scoredRelations
      .map(new RelationToGraphWriteMapFunction)
      .name("map-relations-to-writes")
      .uid("map-relations-to-writes")
    
    // 7e. Combine all writes and send to Neo4j
    val graphWrites: DataStream[GraphWrite] = chunkWrites
      .union(conceptWrites)
      .union(mentionsWrites)
      .union(relationWrites)
    
    graphWrites
      .addSink(new Neo4jSink)
      .name("neo4j-sink")
      .uid("neo4j-sink")
    
    // Also print for debugging
    chunkWithMentions
      .map(new FormatChunkMentionMapFunction)
      .print()
      .name("print-mentions")
    
    // Execute the job
    logger.info("Executing Flink job...")
    val result = env.execute("GraphRAG-FullPipeline")
    
    // In detached mode (Kubernetes), getNetRuntime() is not available
    try {
      logger.info(s"Job completed in ${result.getNetRuntime}ms")
    } catch {
      case _: org.apache.flink.api.common.InvalidProgramException =>
        logger.info("Job submitted in detached mode - execution continues asynchronously")
      case e: Exception =>
        logger.warn(s"Could not get job runtime: ${e.getMessage}")
    }
  }
  
  /**
   * Create and configure the Flink execution environment
   * This method works for BOTH local execution AND cluster submission
   */
  private def createExecutionEnvironment(): StreamExecutionEnvironment = {
    // Use getExecutionEnvironment() which automatically detects:
    // - Local execution when run via IDE or sbt run
    // - Cluster execution when submitted via flink run
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    
    // Configure parallelism
    env.setParallelism(4) // Adjust based on your cluster
    
    // Configure checkpointing (optional, for fault tolerance)
    // env.enableCheckpointing(60000) // checkpoint every 60 seconds
    
    logger.info(s"Flink environment created with parallelism: ${env.getParallelism}")
    
    env
  }
  
  /**
   * Ingest JSONL files as a DataStream
   * 
   * Uses Flink's FileSource API for bounded reading
   * (In production, you could use Kafka or other streaming sources)
   */
  private def ingestJsonl(env: StreamExecutionEnvironment, path: String): DataStream[String] = {
    logger.info(s"Creating FileSource for path: $path")
    
    // Build FileSource for reading text files
    val source = FileSource
      .forRecordStreamFormat(new TextLineInputFormat(), new Path(path))
      .build()
    
    // Create DataStream from source
    val stream = env.fromSource(
      source,
      WatermarkStrategy.noWatermarks(),
      "jsonl-source"
    )
    
    logger.info("FileSource created successfully")
    
    stream
  }
  
}

// Standalone serializable function objects for Flink cluster mode
// These are separate objects to avoid capturing the GraphRagJob object state

object ParseJsonFunction {
  def apply(json: String): Seq[Chunk] = {
    JsonDeser.parseChunk(json).toSeq
  }
}

object ExtractConceptsFunction {
  def apply(chunk: Chunk): Seq[Mentions] = {
    try {
      ConceptExtractor.extractHeuristic(chunk)
    } catch {
      case _: Exception => Seq.empty
    }
  }
}

// RichFlatMapFunction to parse JSON strings into Chunk objects
class ParseJsonFlatMapFunction extends RichFlatMapFunction[String, Chunk] {
  override def flatMap(line: String, out: Collector[Chunk]): Unit = {
    JsonDeser.parseChunk(line) match {
      case Some(chunk) => out.collect(chunk)
      case None => // Skip invalid JSON lines
    }
  }
}

// RichMapFunction to print parsed Chunk objects
class PrintChunkMapFunction extends RichMapFunction[Chunk, String] {
  override def map(chunk: Chunk): String = {
    s"Chunk[${chunk.chunkId.take(12)}...] docId=${chunk.docId.take(12)}... text=${chunk.text.take(50)}..."
  }
}

// RichFlatMapFunction to extract concepts from chunks using Stanford CoreNLP
class ExtractConceptsFlatMapFunction extends RichFlatMapFunction[Chunk, Mentions] {
  override def flatMap(chunk: Chunk, out: Collector[Mentions]): Unit = {
    try {
      // Use NLP-based extraction (Stanford CoreNLP)
      val mentions = ConceptExtractor.extractWithNLP(chunk)
      mentions.foreach(out.collect)
    } catch {
      case e: Exception =>
        // Log error but don't fail the job - just skip this chunk
        // In production, you'd want proper logging here
    }
  }
}

// RichMapFunction to print extracted Mentions (concepts)
class PrintMentionMapFunction extends RichMapFunction[Mentions, String] {
  override def map(mention: Mentions): String = {
    s"Chunk[${mention.chunkId.take(8)}...] -> Concept[${mention.concept.lemma}] (${mention.concept.origin}) surface='${mention.concept.surface}'"
  }
}

/**
 * RichMapFunction to format chunk/mention pairs for logging
 */
class FormatChunkMentionMapFunction extends RichMapFunction[(Chunk, Mentions), String] {
  override def map(value: (Chunk, Mentions)): String = {
    val (chunk, mention) = value
    s"Chunk[${chunk.chunkId.take(8)}...] -> Concept[${mention.concept.lemma}] (${mention.concept.origin})"
  }
}

// ============================================================================
// Phase 3: Relation Extraction Functions
// ============================================================================

/**
 * Extract concepts and keep chunk reference for evidence
 */
class ExtractConceptsWithChunkFlatMapFunction extends RichFlatMapFunction[Chunk, (Chunk, Mentions)] {
  override def flatMap(chunk: Chunk, out: Collector[(Chunk, Mentions)]): Unit = {
    try {
      // Use heuristic extraction instead of NLP to save memory
      val mentions = ConceptExtractor.extractHeuristic(chunk)
      mentions.foreach { mention =>
        out.collect((chunk, mention))
      }
    } catch {
      case e: Exception =>
        // Log error but don't fail the job
    }
  }
}

/**
 * Extract concepts from chunk and create relation candidates for co-occurring concepts
 * This processes all mentions for a chunk together to find pairs
 */
class ExtractConceptsAndCreateCandidatesFlatMapFunction extends RichFlatMapFunction[Chunk, RelationCandidate] {
  override def flatMap(chunk: Chunk, out: Collector[RelationCandidate]): Unit = {
    try {
      // Extract all concepts from this chunk using heuristic extraction (memory-efficient)
      val mentions = ConceptExtractor.extractHeuristic(chunk)
      
      if (mentions.length >= 2) {
        // Extract co-occurrences (concept pairs within the chunk)
        val coOccurs = CoOccurExtractor.extractCoOccurrences(mentions, chunk.chunkId)
        
        // Create relation candidates with evidence text
        coOccurs.foreach { coOccur =>
          val candidate = RelationCandidate(
            a = coOccur.a,
            b = coOccur.b,
            evidence = chunk.text.take(500) // Limit evidence text length
          )
          out.collect(candidate)
        }
      }
    } catch {
      case e: Exception =>
        // Log error but don't fail the job
    }
  }
}

/**
 * Blocking FlatMap to score relations using LLM
 * (In production, use Async I/O for better throughput)
 */
class ScoreRelationFlatMapFunction extends RichFlatMapFunction[RelationCandidate, ScoredRelation] {
  // Use @transient lazy val to avoid serialization issues
  // This will be initialized on the worker node, not serialized
  @transient private lazy implicit val ec: ExecutionContext = ExecutionContext.global
  private val timeout: FiniteDuration = 30.seconds

  override def flatMap(candidate: RelationCandidate, out: Collector[ScoredRelation]): Unit = {
    try {
      val verdictOpt = Await.result(RelationScorer.scoreRelation(candidate), timeout)
      verdictOpt.filter(RelationScorer.meetsThreshold).foreach { verdict =>
        val scoredRelation = ScoredRelation(
          a = candidate.a,
          predicate = verdict.predicate,
          b = candidate.b,
          confidence = verdict.confidence,
          evidence = verdict.evidence
        )
        out.collect(scoredRelation)
      }
    } catch {
      case _: Exception =>
        // Skip on error/timeout
    }
  }
}

/**
 * Extract Concept from (Chunk, Mentions) tuple
 */
class ExtractConceptFromTupleMapFunction extends RichMapFunction[(Chunk, Mentions), Concept] {
  override def map(value: (Chunk, Mentions)): Concept = {
    value._2.concept
  }
}

// ============================================================================
// GraphWrite Converter Functions
// ============================================================================

/**
 * Convert Chunk to UpsertNode GraphWrite
 */
class ChunkToGraphWriteMapFunction extends RichMapFunction[Chunk, GraphWrite] {
  override def map(chunk: Chunk): GraphWrite = {
    import graphrag.core.UpsertNode
    
    UpsertNode(
      label = "Chunk",
      id = chunk.chunkId,
      props = Map(
        "chunkId" -> chunk.chunkId,
        "docId" -> chunk.docId,
        "text" -> chunk.text,
        "sourceUri" -> chunk.sourceUri,
        "hash" -> chunk.hash,
        "spanStart" -> chunk.span.start,
        "spanEnd" -> chunk.span.end
      )
    )
  }
}

/**
 * Convert Concept to UpsertNode GraphWrite
 */
class ConceptToGraphWriteMapFunction extends RichMapFunction[Concept, GraphWrite] {
  override def map(concept: Concept): GraphWrite = {
    import graphrag.core.UpsertNode
    
    UpsertNode(
      label = "Concept",
      id = concept.conceptId,
      props = Map(
        "conceptId" -> concept.conceptId,
        "lemma" -> concept.lemma,
        "surface" -> concept.surface,
        "origin" -> concept.origin
      )
    )
  }
}

/**
 * Convert (Chunk, Mentions) to MENTIONS edge GraphWrite
 */
class MentionsToGraphWriteMapFunction extends RichMapFunction[(Chunk, Mentions), GraphWrite] {
  override def map(value: (Chunk, Mentions)): GraphWrite = {
    import graphrag.core.UpsertEdge
    
    val (chunk, mentions) = value
    UpsertEdge(
      fromLabel = "Chunk",
      fromId = chunk.chunkId,
      rel = "MENTIONS",
      toLabel = "Concept",
      toId = mentions.concept.conceptId,
      props = Map.empty[String, Any]
    )
  }
}

/**
 * Convert ScoredRelation to typed edge GraphWrite
 */
class RelationToGraphWriteMapFunction extends RichMapFunction[ScoredRelation, GraphWrite] {
  override def map(relation: ScoredRelation): GraphWrite = {
    import graphrag.core.UpsertEdge
    
    UpsertEdge(
      fromLabel = "Concept",
      fromId = relation.a.conceptId,
      rel = relation.predicate,
      toLabel = "Concept",
      toId = relation.b.conceptId,
      props = Map(
        "confidence" -> Double.box(relation.confidence),
        "evidence" -> relation.evidence
      )
    )
  }
}

