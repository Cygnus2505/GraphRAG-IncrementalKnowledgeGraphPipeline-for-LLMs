package graphrag.llm

import graphrag.core.LlmVerdict
import com.typesafe.config.ConfigFactory
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import org.slf4j.LoggerFactory
import sttp.client3._
import sttp.client3.circe._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

/**
 * HTTP client for Ollama LLM API
 * 
 * Supports async requests for relation scoring.
 */
object OllamaClient {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private val appConfig = ConfigFactory.load()
  
  // Prioritize environment variable for endpoint (useful for Kubernetes)
  private val endpoint = sys.env.getOrElse("OLLAMA_ENDPOINT", appConfig.getString("ollama.endpoint"))
  private val model = appConfig.getString("ollama.model")
  private val temperature = appConfig.getDouble("ollama.temperature")
  private val timeoutMs = appConfig.getInt("ollama.timeout-ms")
  private val maxRetries = appConfig.getInt("ollama.max-retries")
  
  logger.info(s"Ollama endpoint configured: $endpoint")
  
  // STTP backend (synchronous for now, can be made async)
  private implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  
  /**
   * Request structure for Ollama API
   */
  case class OllamaRequest(
    model: String,
    prompt: String,
    stream: Boolean = false,
    options: Map[String, Double] = Map("temperature" -> temperature)
  )
  
  /**
   * Response structure from Ollama API
   */
  case class OllamaResponse(
    model: String,
    created_at: String,
    response: String,
    done: Boolean
  )
  
  /**
   * Send a prompt to Ollama and get response
   * 
   * @param prompt The prompt text
   * @return Future containing the LLM response text
   */
  def generate(prompt: String)(implicit ec: ExecutionContext): Future[String] = {
    Future {
      val request = OllamaRequest(
        model = model,
        prompt = prompt,
        stream = false
      )
      
      val url = s"$endpoint/api/generate"
      
      var lastError: Option[Exception] = None
      var attempt = 0
      var result: Option[String] = None
      
      while (attempt < maxRetries && result.isEmpty) {
        try {
          val response = basicRequest
            .post(uri"$url")
            .body(request.asJson.noSpaces)
            .header("Content-Type", "application/json")
            .readTimeout(timeoutMs.millis)
            .send(backend)
          
          response.body match {
            case Right(body: String) =>
              decode[OllamaResponse](body) match {
                case Right(ollamaResp) =>
                  logger.debug(s"Ollama response received: ${ollamaResp.response.take(100)}...")
                  result = Some(ollamaResp.response)
                case Left(error) =>
                  logger.error(s"Failed to parse Ollama response: $error")
                  lastError = Some(new RuntimeException(s"JSON parse error: $error"))
              }
            case Left(error) =>
              logger.warn(s"Ollama API error (attempt ${attempt + 1}/$maxRetries): $error")
              lastError = Some(new RuntimeException(s"HTTP error: $error"))
          }
        } catch {
          case e: Exception =>
            logger.warn(s"Ollama request failed (attempt ${attempt + 1}/$maxRetries): ${e.getClass.getSimpleName}: ${e.getMessage}")
            logger.debug(s"Full exception: ", e)
            lastError = Some(e)
        }
        
        attempt += 1
        if (attempt < maxRetries && result.isEmpty) {
          Thread.sleep(1000 * attempt) // Exponential backoff
        }
      }
      
      // Return result or throw error
      result match {
        case Some(response) => response
        case None =>
          val error = lastError.getOrElse(new RuntimeException("Unknown error"))
          logger.error(s"Ollama request failed after $maxRetries attempts: ${error.getMessage}")
          throw error
      }
    }(ec)
  }
  
  /**
   * Check if Ollama is available
   * 
   * @return True if Ollama responds, false otherwise
   */
  def isAvailable(): Boolean = {
    try {
      val response = basicRequest
        .get(uri"$endpoint/api/tags")
        .readTimeout(5.seconds)
        .send(backend)
      
      response.isSuccess
    } catch {
      case _: Exception => false
    }
  }
}

