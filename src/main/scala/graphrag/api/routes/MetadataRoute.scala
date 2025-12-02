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
 * Metadata API Routes
 * GET /v1/metadata - Returns graph statistics
 */
class MetadataRoute(neo4jService: Neo4jQueryService) {
  
  val routes: Route =
    pathPrefix("v1" / "metadata") {
      get {
        // GET /v1/metadata
        onComplete(Future.fromTry(neo4jService.getGraphMetadata)) {
          case Success(metadata) =>
            complete(StatusCodes.OK, ApiResponse(
              success = true,
              data = Some(metadata),
              traceId = Some(java.util.UUID.randomUUID().toString)
            ))
          
          case Failure(exception) =>
            complete(StatusCodes.InternalServerError, ApiResponse[GraphMetadata](
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

