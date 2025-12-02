package graphrag.core

/**
 * Core data models for GraphRAG pipeline
 * These match the schema from the homework specification
 */

// ============================================================================
// Input Models (from JSONL)
// ============================================================================

/**
 * Chunk represents a text chunk with metadata
 * This is the input from Phase 1 (Delta export)
 */
case class Chunk(
  chunkId: String,
  docId: String,
  span: Span,
  text: String,
  sourceUri: String,
  hash: String
)

case class Span(start: Int, end: Int)

// ============================================================================
// Graph Node Models
// ============================================================================

/**
 * Concept represents a semantic entity extracted from text
 * Origin can be: NER, keyphrase, title, tag, llm
 */
case class Concept(
  conceptId: String,
  lemma: String,
  surface: String,
  origin: String
)

/**
 * Mentions represents the relationship between a Chunk and a Concept
 */
case class Mentions(
  chunkId: String,
  concept: Concept
)

// ============================================================================
// Relation Models
// ============================================================================

/**
 * Co-occurrence of concepts within a window
 */
case class CoOccur(
  a: Concept,
  b: Concept,
  windowId: String,
  freq: Long
)

/**
 * Relation candidate before LLM scoring
 */
case class RelationCandidate(
  a: Concept,
  b: Concept,
  evidence: String
)

/**
 * Scored relation after LLM processing
 */
case class ScoredRelation(
  a: Concept,
  predicate: String,
  b: Concept,
  confidence: Double,
  evidence: String
)

/**
 * LLM verdict structure for relation scoring
 */
case class LlmVerdict(
  predicate: String,
  confidence: Double,
  evidence: String,
  ref: String
)

// ============================================================================
// Graph Write Models (for Neo4j)
// ============================================================================

/**
 * Graph write operations for Neo4j sink
 */
sealed trait GraphWrite

case class UpsertNode(
  label: String,
  id: String,
  props: Map[String, Any]
) extends GraphWrite

case class UpsertEdge(
  fromLabel: String,
  fromId: String,
  rel: String,
  toLabel: String,
  toId: String,
  props: Map[String, Any]
) extends GraphWrite






























