package graphrag.api.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import graphrag.api.models._
import graphrag.api.models.JsonFormats._
import graphrag.api.services.Neo4jQueryService
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Evidence API Routes
 * GET /v1/evidence/:conceptId - Returns all chunks that mention a concept (provenance)
 */
class EvidenceRoute(neo4jService: Neo4jQueryService) {
  
  val routes: Route =
    pathPrefix("v1" / "evidence") {
      path(Segment) { conceptId =>
        get {
          // GET /v1/evidence/:conceptId
          onComplete(Future.fromTry(neo4jService.getChunksMentioningConcept(conceptId))) {
            case Success(evidence) =>
              complete(StatusCodes.OK, ApiResponse(
                success = true,
                data = Some(evidence),
                traceId = Some(java.util.UUID.randomUUID().toString)
              ))
            
            case Failure(error: NoSuchElementException) =>
              complete(StatusCodes.NotFound, ApiResponse[EvidenceResponse](
                success = false,
                error = Some(ApiError(
                  code = "CONCEPT_NOT_FOUND",
                  message = s"Concept not found: $conceptId",
                  details = Some(error.getMessage)
                ))
              ))
            
            case Failure(exception) =>
              complete(StatusCodes.InternalServerError, ApiResponse[EvidenceResponse](
                success = false,
                error = Some(ApiError(
                  code = "INTERNAL_ERROR",
                  message = exception.getMessage,
                  details = Some(exception.getClass.getName)
                ))
              ))
          }
        }
      }
    }
}

