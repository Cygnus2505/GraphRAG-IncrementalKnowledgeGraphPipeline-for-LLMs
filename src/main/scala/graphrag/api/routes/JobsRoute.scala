package graphrag.api.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import graphrag.api.models._
import graphrag.api.models.JsonFormats._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

/**
 * Jobs API Routes
 * POST /v1/jobs - Submit async query job
 * GET /v1/jobs/:jobId - Get job status
 * 
 * Simplified implementation for homework (in-memory job tracking)
 * Production version would use Kafka/Flink for actual async processing
 */
class JobsRoute {
  
  // In-memory job store (would be a database in production)
  private val jobs = new ConcurrentHashMap[String, JobStatus]()
  
  val routes: Route =
    pathPrefix("v1" / "jobs") {
      concat(
        // POST /v1/jobs - Submit new async job
        pathEnd {
          post {
            entity(as[QueryRequest]) { request =>
              val jobId = UUID.randomUUID().toString
              val now = java.time.Instant.now().toString
              
              val job = JobStatus(
                jobId = jobId,
                state = "PENDING",
                startedAt = Some(now),
                finishedAt = None,
                progress = Some(0),
                resultLink = Some(s"/v1/jobs/$jobId/result"),
                error = None
              )
              
              jobs.put(jobId, job)
              
              // In a real implementation, you would submit this to a queue here
              // For homework, we'll just mark it as completed immediately
              val completedJob = job.copy(
                state = "SUCCEEDED",
                finishedAt = Some(java.time.Instant.now().toString),
                progress = Some(100)
              )
              jobs.put(jobId, completedJob)
              
              complete(StatusCodes.Accepted, ApiResponse(
                success = true,
                data = Some(job),
                traceId = Some(UUID.randomUUID().toString)
              ))
            }
          }
        },
        
        // GET /v1/jobs/:jobId - Get job status
        path(Segment) { jobId =>
          get {
            Option(jobs.get(jobId)) match {
              case Some(job) =>
                complete(StatusCodes.OK, ApiResponse(
                  success = true,
                  data = Some(job),
                  traceId = Some(UUID.randomUUID().toString)
                ))
              
              case None =>
                complete(StatusCodes.NotFound, ApiResponse[JobStatus](
                  success = false,
                  error = Some(ApiError(
                    code = "JOB_NOT_FOUND",
                    message = s"Job not found: $jobId"
                  ))
                ))
            }
          }
        },
        
        // GET /v1/jobs/:jobId/result - Get job result
        path(Segment / "result") { jobId =>
          get {
            Option(jobs.get(jobId)) match {
              case Some(job) if job.state == "SUCCEEDED" =>
                // In production, you'd fetch actual results from storage
                complete(StatusCodes.OK, ApiResponse(
                  success = true,
                  data = Some(QueryResponse(
                    mode = "async",
                    summary = Some("Async job completed successfully"),
                    results = Some(Seq.empty)
                  )),
                  traceId = Some(UUID.randomUUID().toString)
                ))
              
              case Some(job) if job.state == "PENDING" || job.state == "RUNNING" =>
                complete(StatusCodes.Accepted, ApiResponse[QueryResponse](
                  success = false,
                  error = Some(ApiError(
                    code = "JOB_NOT_READY",
                    message = s"Job is still ${job.state.toLowerCase}"
                  ))
                ))
              
              case Some(job) if job.state == "FAILED" =>
                complete(StatusCodes.InternalServerError, ApiResponse[QueryResponse](
                  success = false,
                  error = Some(ApiError(
                    code = "JOB_FAILED",
                    message = job.error.getOrElse("Job failed")
                  ))
                ))
              
              case None =>
                complete(StatusCodes.NotFound, ApiResponse[QueryResponse](
                  success = false,
                  error = Some(ApiError(
                    code = "JOB_NOT_FOUND",
                    message = s"Job not found: $jobId"
                  ))
                ))
            }
          }
        }
      )
    }
}

