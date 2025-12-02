package graphrag.api.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import graphrag.api.models._
import graphrag.api.models.JsonFormats._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

/**
 * Explain API Routes
 * GET /v1/explain/trace/:requestId - Returns execution trace for a query
 */
class ExplainRoute {
  
  val routes: Route =
    pathPrefix("v1" / "explain" / "trace") {
      path(Segment) { requestId =>
        get {
          // GET /v1/explain/trace/:requestId
          // For homework, generate a mock trace
          val mockTrace = ExecutionTrace(
            requestId = requestId,
            query = "Concept search query",
            plan = Seq(
              PlanStep(
                step = "matchConcepts",
                cypher = Some("MATCH (c:Concept) WHERE c.lemma CONTAINS $pattern"),
                detail = Some("Find concepts matching pattern")
              ),
              PlanStep(
                step = "getRelations",
                cypher = Some("MATCH (c)-[r]-(related:Concept)"),
                detail = Some("Get related concepts")
              ),
              PlanStep(
                step = "aggregate",
                cypher = None,
                detail = Some("Aggregate and sort results")
              )
            ),
            counters = ExecutionCounters(
              nodesRead = 275,
              relationshipsRead = 4376,
              llmCalls = 0,
              cacheHits = 1
            ),
            executionTimeMs = 45,
            promptVersions = Some(Map(
              "relationScoring" -> "v3.2",
              "conceptExtraction" -> "v2.1"
            ))
          )
          
          complete(StatusCodes.OK, ApiResponse(
            success = true,
            data = Some(mockTrace),
            traceId = Some(java.util.UUID.randomUUID().toString)
          ))
        }
      }
    }
}
