package graphrag.api.models

import spray.json._

/**
 * JSON formatters for Akka HTTP spray-json
 * Defines how to serialize/deserialize case classes to/from JSON
 */
object JsonFormats extends DefaultJsonProtocol {
  
  // Common
  implicit val apiErrorFormat: RootJsonFormat[ApiError] = jsonFormat3(ApiError)
  implicit def apiResponseFormat[T: JsonFormat]: RootJsonFormat[ApiResponse[T]] = jsonFormat4(ApiResponse.apply[T])
  
  // MetadataService
  implicit val graphMetadataFormat: RootJsonFormat[GraphMetadata] = jsonFormat6(GraphMetadata)
  
  // EvidenceService
  implicit val spanInfoFormat: RootJsonFormat[SpanInfo] = jsonFormat2(SpanInfo)
  implicit val chunkEvidenceFormat: RootJsonFormat[ChunkEvidence] = jsonFormat5(ChunkEvidence)
  implicit val evidenceResponseFormat: RootJsonFormat[EvidenceResponse] = jsonFormat5(EvidenceResponse)
  
  // ExploreService
  implicit val conceptNodeFormat: RootJsonFormat[ConceptNode] = jsonFormat4(ConceptNode)
  implicit val conceptRelationFormat: RootJsonFormat[ConceptRelation] = jsonFormat5(ConceptRelation)
  implicit val exploreResponseFormat: RootJsonFormat[ExploreResponse] = jsonFormat4(ExploreResponse)
  
  // QueryService
  implicit val timeRangeFormat: RootJsonFormat[TimeRange] = jsonFormat2(TimeRange)
  implicit val queryConstraintsFormat: RootJsonFormat[QueryConstraints] = jsonFormat3(QueryConstraints)
  implicit val outputConfigFormat: RootJsonFormat[OutputConfig] = jsonFormat4(OutputConfig)
  implicit val queryRequestFormat: RootJsonFormat[QueryRequest] = jsonFormat4(QueryRequest)
  implicit val queryResultFormat: RootJsonFormat[QueryResult] = jsonFormat4(QueryResult)
  implicit val queryGroupFormat: RootJsonFormat[QueryGroup] = jsonFormat2(QueryGroup)
  implicit val queryResponseFormat: RootJsonFormat[QueryResponse] = jsonFormat8(QueryResponse)
  
  // JobsService
  implicit val jobStatusFormat: RootJsonFormat[JobStatus] = jsonFormat7(JobStatus)
  
  // ExplainService
  implicit val planStepFormat: RootJsonFormat[PlanStep] = jsonFormat3(PlanStep)
  implicit val executionCountersFormat: RootJsonFormat[ExecutionCounters] = jsonFormat4(ExecutionCounters)
  implicit val executionTraceFormat: RootJsonFormat[ExecutionTrace] = jsonFormat6(ExecutionTrace)
}
