package graphrag.api

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import graphrag.api.routes._
import graphrag.api.services.Neo4jQueryService
import org.slf4j.LoggerFactory

import scala.io.StdIn

/**
 * Main API Server for GraphRAG REST API
 * Starts HTTP server on port 8080 and handles all REST endpoints
 */
object ApiServer {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  def main(args: Array[String]): Unit = {
    logger.info("Starting GraphRAG API Server...")
    
    // Initialize Akka Actor System
    implicit val system: ActorSystem = ActorSystem("GraphRagApi")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    
    // Initialize Neo4j service
    val neo4jService = new Neo4jQueryService()
    try {
      neo4jService.connect()
      logger.info("Neo4j connection established")
    } catch {
      case e: Exception =>
        logger.error("Failed to connect to Neo4j", e)
        logger.error("Server will start but Neo4j-dependent endpoints will fail")
    }
    
    // Create route instances
    val metadataRoute = new MetadataRoute(neo4jService)
    val evidenceRoute = new EvidenceRoute(neo4jService)
    val exploreRoute = new ExploreRoute(neo4jService)
    val jobsRoute = new JobsRoute()
    val queryRoute = new QueryRoute(neo4jService)
    queryRoute.setJobsRoute(jobsRoute) // Enable async mode support
    val explainRoute = new ExplainRoute()
    
    // Combine all routes
    val routes: Route =
      path("health") {
        get {
          complete("OK")
        }
      } ~
      metadataRoute.routes ~
      evidenceRoute.routes ~
      exploreRoute.routes ~
      queryRoute.routes ~
      jobsRoute.routes ~
      explainRoute.routes
    
    // Start HTTP server
    val host = "0.0.0.0"
    val port = 8080
    
    val bindingFuture = Http().bindAndHandle(routes, host, port)
    
    bindingFuture.onComplete {
      case scala.util.Success(binding) =>
        val address = binding.localAddress
        logger.info(s"🚀 GraphRAG API Server online at http://${address.getHostString}:${address.getPort}/")
        logger.info(s"📖 API Documentation: http://${address.getHostString}:${address.getPort}/")
        logger.info("Server is running. Press Ctrl+C or RETURN to stop...")
      
      case scala.util.Failure(ex) =>
        logger.error(s"Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
    
    // Register shutdown hook for graceful shutdown
    sys.addShutdownHook {
      logger.info("Shutdown signal received, shutting down gracefully...")
      bindingFuture
        .flatMap(_.unbind())
        .onComplete { _ =>
          neo4jService.close()
          system.terminate()
        }
    }
    
    // Wait for user input to stop the server, but handle cases where stdin is not available
    try {
      val input = StdIn.readLine()
      if (input != null) {
        logger.info("Shutting down...")
        bindingFuture
          .flatMap(_.unbind())
          .onComplete { _ =>
            neo4jService.close()
            system.terminate()
          }
      }
    } catch {
      case e: Exception =>
        // If stdin is not available, keep the server running
        logger.info("Running in background mode (stdin not available). Server will keep running.")
        logger.info("Use Ctrl+C or kill the process to stop the server.")
        // Keep the main thread alive by waiting for system termination
        scala.concurrent.Await.result(system.whenTerminated, scala.concurrent.duration.Duration.Inf)
    }
  }
}
