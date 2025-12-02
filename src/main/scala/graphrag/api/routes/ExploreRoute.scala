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
 * Explore API Routes
 * GET /v1/explore/:conceptId - Returns neighboring concepts and their relations
 */
class ExploreRoute(neo4jService: Neo4jQueryService) {
  
  val routes: Route =
    pathPrefix("v1" / "graph" / "concept") {
      path(Segment / "neighbors") { conceptId =>
        get {
          parameters(
            "limit".as[Int].?(20),
            "direction".?("both"),
            "depth".as[Int].?(1),
            "edgeTypes".?
          ) { (limit, direction, depth, edgeTypes) =>
            // GET /v1/graph/concept/:conceptId/neighbors?limit=20&direction=both&depth=1
            onComplete(Future.fromTry(neo4jService.getConceptNeighbors(conceptId, limit))) {
              case Success(explore) =>
                complete(StatusCodes.OK, ApiResponse(
                  success = true,
                  data = Some(explore),
                  traceId = Some(java.util.UUID.randomUUID().toString)
                ))
              
              case Failure(error: NoSuchElementException) =>
                complete(StatusCodes.NotFound, ApiResponse[ExploreResponse](
                  success = false,
                  error = Some(ApiError(
                    code = "CONCEPT_NOT_FOUND",
                    message = s"Concept not found: $conceptId",
                    details = Some(error.getMessage)
                  ))
                ))
              
              case Failure(exception) =>
                complete(StatusCodes.InternalServerError, ApiResponse[ExploreResponse](
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
}

