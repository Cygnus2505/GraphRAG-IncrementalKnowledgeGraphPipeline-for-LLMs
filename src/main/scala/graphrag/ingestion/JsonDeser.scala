package graphrag.ingestion

import graphrag.core.{Chunk, Span}
import io.circe._
import io.circe.parser._
import io.circe.generic.semiauto._
import org.slf4j.LoggerFactory

/**
 * JSON deserialization utilities for the Flink pipeline
 * Uses Circe for type-safe JSON parsing
 */
object JsonDeser {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  // Circe decoders (implicit for automatic derivation)
  implicit val spanDecoder: Decoder[Span] = deriveDecoder[Span]
  implicit val chunkDecoder: Decoder[Chunk] = deriveDecoder[Chunk]
  
  /**
   * Parse a JSON string into a Chunk
   * Returns None if parsing fails
   */
  def parseChunk(json: String): Option[Chunk] = {
    decode[Chunk](json) match {
      case Right(chunk) => Some(chunk)
      case Left(error) =>
        logger.warn(s"Failed to parse chunk: ${error.getMessage}")
        logger.debug(s"JSON that failed to parse: $json")
        None
    }
  }
  
  /**
   * Parse a JSON string into a Chunk (throws on failure)
   * Use this when you want to fail fast on bad data
   */
  def parseChunkStrict(json: String): Chunk = {
    decode[Chunk](json) match {
      case Right(chunk) => chunk
      case Left(error) =>
        logger.error(s"Failed to parse chunk: ${error.getMessage}")
        logger.error(s"JSON: $json")
        throw new IllegalArgumentException(s"Invalid JSON: ${error.getMessage}")
    }
  }
}






























