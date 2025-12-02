package graphrag.api.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import graphrag.api.models._
import graphrag.api.models.JsonFormats._
import graphrag.api.services.Neo4jQueryService
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Query API Routes
 * POST /v1/query - Execute graph queries
 * Supports both synchronous and asynchronous modes
 */
class QueryRoute(neo4jService: Neo4jQueryService) {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  // Reference to JobsRoute for async job submission (would be injected in production)
  private var jobsRoute: Option[JobsRoute] = None
  
  def setJobsRoute(jobsRoute: JobsRoute): Unit = {
    this.jobsRoute = Some(jobsRoute)
  }
  
  /**
   * Determine if query should be processed asynchronously
   * Criteria: complex queries with grouping, timeRange, or constraints
   */
  private def shouldUseAsyncMode(request: QueryRequest): Boolean = {
    request.output.exists(_.groupBy.exists(_.nonEmpty)) ||
    request.timeRange.isDefined ||
    request.constraints.exists(c => c.datasets.exists(_.nonEmpty) || c.baselines.exists(_.nonEmpty))
  }
  
  /**
   * Group results by specified keys
   */
  private def groupResults(results: Seq[QueryResult], groupBy: Option[Seq[String]]): Option[Seq[QueryGroup]] = {
    groupBy.flatMap { keys =>
      if (keys.isEmpty || keys.headOption.isEmpty) {
        None
      } else {
        // Simple grouping: group by first key (for homework, we'll use a simple heuristic)
        // In production, this would extract metadata from concepts
        val grouped = results.groupBy { result =>
          // For demo: use first part of lemma as group key
          val firstKey = keys.head
          val groupValue = result.lemma.split("_").headOption.getOrElse("other")
          Map(firstKey -> groupValue)
        }
        
        Some(grouped.map { case (key, items) =>
          QueryGroup(key = key, items = items)
        }.toSeq)
      }
    }
  }
  
  val routes: Route =
    pathPrefix("v1" / "query") {
      post {
        entity(as[QueryRequest]) { request =>
          // POST /v1/query
          
          val traceId = UUID.randomUUID().toString
          val requestId = s"req-${traceId.take(8)}"
          
          // Check if query should be async
          if (shouldUseAsyncMode(request) && jobsRoute.isDefined) {
            // Return async response (202 Accepted)
            val jobId = UUID.randomUUID().toString
            val statusLink = s"/v1/jobs/$jobId"
            
            // Submit job (simplified - in production would use actual job queue)
            jobsRoute.foreach { jobs =>
              // Create a job status (simplified)
              val job = JobStatus(
                jobId = jobId,
                state = "PENDING",
                startedAt = Some(java.time.Instant.now().toString),
                finishedAt = None,
                progress = Some(0),
                resultLink = Some(s"/v1/jobs/$jobId/result"),
                error = None
              )
              // In production, would submit to actual job queue
            }
            
            complete(StatusCodes.Accepted, ApiResponse(
              success = true,
              data = Some(QueryResponse(
                mode = "async",
                summary = Some("Query submitted for asynchronous processing"),
                results = None,
                groups = None,
                jobId = Some(jobId),
                statusLink = Some(statusLink),
                evidenceAvailable = Some(true),
                explainLink = Some(s"/v1/explain/trace/$requestId")
              )),
              traceId = Some(traceId)
            ))
          } else {
            // Synchronous processing
            val topK = request.output.flatMap(_.topKPerGroup).getOrElse(20)
            
            onComplete(Future.fromTry(neo4jService.searchConcepts(request.query, topK))) {
              case Success(results) =>
                // Group results if requested
                val groups = groupResults(results, request.output.flatMap(_.groupBy))
                
                // Check if evidence is available (simplified: assume true if results exist)
                val evidenceAvailable = results.nonEmpty
                
                // Generate explain link
                val explainLink = s"/v1/explain/trace/$requestId"
                
                complete(StatusCodes.OK, ApiResponse(
                  success = true,
                  data = Some(QueryResponse(
                    mode = "sync",
                    summary = Some(s"Found ${results.length} concepts matching '${request.query}'"),
                    results = Some(results),
                    groups = groups,
                    jobId = None,
                    statusLink = None,
                    evidenceAvailable = Some(evidenceAvailable),
                    explainLink = Some(explainLink)
                  )),
                  traceId = Some(traceId)
                ))
              
              case Failure(exception) =>
                logger.error(s"Query failed for pattern '${request.query}': ${exception.getMessage}", exception)
                complete(StatusCodes.InternalServerError, ApiResponse[QueryResponse](
                  success = false,
                  error = Some(ApiError(
                    code = "INTERNAL_ERROR",
                    message = exception.getMessage,
                    details = Some(s"${exception.getClass.getName}: ${exception.getStackTrace.take(3).mkString("; ")}")
                  )),
                  traceId = Some(traceId)
                ))
            }
          }
        }
      }
    }
}
