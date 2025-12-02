package graphrag.api.models

/**
 * API Models (DTOs) for REST API
 * All request and response models
 */

// ============================================================================
// Common Response Wrapper
// ============================================================================

case class ApiResponse[T](
  success: Boolean,
  data: Option[T] = None,
  error: Option[ApiError] = None,
  traceId: Option[String] = None
)

case class ApiError(
  code: String,
  message: String,
  details: Option[String] = None
)

// ============================================================================
// MetadataService Models
// ============================================================================

case class GraphMetadata(
  nodeCount: Long,
  edgeCount: Long,
  conceptCount: Long,
  chunkCount: Long,
  relationTypes: Map[String, Long],
  lastUpdated: Option[String] = None
)

// ============================================================================
// EvidenceService Models
// ============================================================================

case class EvidenceResponse(
  conceptId: String,
  lemma: String,
  surface: String,
  chunks: Seq[ChunkEvidence],
  totalMentions: Int
)

case class ChunkEvidence(
  chunkId: String,
  docId: String,
  text: String,
  sourceUri: String,
  span: SpanInfo
)

case class SpanInfo(
  start: Int,
  end: Int
)

// ============================================================================
// ExploreService Models
// ============================================================================

case class ExploreResponse(
  center: ConceptNode,
  neighbors: Seq[ConceptNode],
  relations: Seq[ConceptRelation],
  totalNeighbors: Int
)

case class ConceptNode(
  conceptId: String,
  lemma: String,
  surface: String,
  origin: String
)

case class ConceptRelation(
  fromConceptId: String,
  toConceptId: String,
  relationType: String,
  confidence: Option[Double] = None,
  evidence: Option[String] = None
)

// ============================================================================
// QueryService Models
// ============================================================================

case class QueryRequest(
  query: String,
  timeRange: Option[TimeRange] = None,
  constraints: Option[QueryConstraints] = None,
  output: Option[OutputConfig] = None
)

case class TimeRange(
  from: Int,
  to: Int
)

case class QueryConstraints(
  datasets: Option[Seq[String]] = None,
  baselines: Option[Seq[String]] = None,
  metrics: Option[Seq[String]] = None
)

case class OutputConfig(
  groupBy: Option[Seq[String]] = None,
  metrics: Option[Seq[String]] = None,
  topKPerGroup: Option[Int] = None,
  includeCitations: Option[Boolean] = None
)

case class QueryResponse(
  mode: String, // "sync" or "async"
  summary: Option[String] = None,
  results: Option[Seq[QueryResult]] = None,
  groups: Option[Seq[QueryGroup]] = None,
  jobId: Option[String] = None,
  statusLink: Option[String] = None,
  evidenceAvailable: Option[Boolean] = None,
  explainLink: Option[String] = None
)

case class QueryResult(
  conceptId: String,
  lemma: String,
  relatedConcepts: Seq[String],
  score: Double
)

case class QueryGroup(
  key: Map[String, String],
  items: Seq[QueryResult]
)

// ============================================================================
// JobsService Models
// ============================================================================

case class JobStatus(
  jobId: String,
  state: String, // "PENDING", "RUNNING", "SUCCEEDED", "FAILED"
  startedAt: Option[String] = None,
  finishedAt: Option[String] = None,
  progress: Option[Int] = None,
  resultLink: Option[String] = None,
  error: Option[String] = None
)

// ============================================================================
// ExplainService Models
// ============================================================================

case class ExecutionTrace(
  requestId: String,
  query: String,
  plan: Seq[PlanStep],
  counters: ExecutionCounters,
  executionTimeMs: Long,
  promptVersions: Option[Map[String, String]] = None
)

case class PlanStep(
  step: String,
  cypher: Option[String] = None,
  detail: Option[String] = None
)

case class ExecutionCounters(
  nodesRead: Long,
  relationshipsRead: Long,
  llmCalls: Int,
  cacheHits: Int
)
