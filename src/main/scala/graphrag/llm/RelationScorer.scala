package graphrag.llm

import graphrag.core.{Concept, LlmVerdict, RelationCandidate}
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

/**
 * Relation scoring using LLM (Ollama)
 * 
 * Sends concept pairs to LLM and extracts relationship predicates.
 */
object RelationScorer {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private val appConfig = ConfigFactory.load()
  
  private val predicateSet = appConfig.getStringList("relation.llm.predicate-set").asScala.toSet
  private val minConfidence = appConfig.getDouble("relation.llm.min-confidence")
  
  // Regex patterns for parsing LLM responses
  private val PredicatePattern: Regex = """predicate[:\s]+([a-z_]+)""".r("predicate")
  private val ConfidencePattern: Regex = """confidence[:\s]+([0-9.]+)""".r("confidence")
  private val EvidencePattern: Regex = """evidence[:\s]+"([^"]+)"""".r("evidence")
  
  /**
   * Generate a prompt for relation scoring
   * 
   * @param candidate The relation candidate (concept pair with evidence)
   * @return Prompt string for LLM
   */
  def generatePrompt(candidate: RelationCandidate): String = {
    val conceptA = candidate.a.lemma
    val conceptB = candidate.b.lemma
    val evidence = candidate.evidence
    
    s"""You are a knowledge graph relation extractor. Given two concepts and their context, determine the relationship between them.

Concepts:
- Concept A: $conceptA
- Concept B: $conceptB

Context (evidence text):
"$evidence"

Available relationship types (predicates):
${predicateSet.mkString("- ", "\n- ", "")}

Task: Determine the relationship between Concept A and Concept B.

Respond in the following JSON format:
{
  "predicate": "<one of the available predicates, or 'related_to' if none fit>",
  "confidence": <0.0 to 1.0>,
  "evidence": "<short quote from context that supports this relationship>",
  "ref": "<conceptA>_<predicate>_<conceptB>"
}

If the concepts are not meaningfully related, set confidence to 0.0."""
  }
  
  /**
   * Score a relation candidate using LLM
   * 
   * @param candidate The relation candidate
   * @return Future containing LLM verdict (predicate, confidence, evidence)
   */
  def scoreRelation(candidate: RelationCandidate)(implicit ec: ExecutionContext): Future[Option[LlmVerdict]] = {
    val prompt = generatePrompt(candidate)
    
    OllamaClient.generate(prompt).map { response =>
      parseVerdict(response, candidate)
    }.recover {
      case e: Exception =>
        logger.warn(s"Failed to score relation ${candidate.a.lemma} -> ${candidate.b.lemma}: ${e.getMessage}")
        None
    }
  }
  
  /**
   * Parse LLM response into LlmVerdict
   * 
   * @param response LLM response text
   * @param candidate Original relation candidate
   * @return Parsed verdict or None if parsing fails
   */
  private def parseVerdict(response: String, candidate: RelationCandidate): Option[LlmVerdict] = {
    try {
      // Try to extract JSON from response (might be wrapped in markdown or text)
      val jsonMatch = """\{[^}]+\}""".r.findFirstIn(response)
      val jsonText = jsonMatch.getOrElse(response)
      
      // Try to parse as JSON first
      import io.circe.parser._
      import io.circe.generic.auto._
      
      decode[LlmVerdict](jsonText) match {
        case Right(verdict) =>
          // Validate predicate is in allowed set
          val validPredicate = if (predicateSet.contains(verdict.predicate)) {
            verdict.predicate
          } else {
            "related_to" // Default fallback
          }
          
          // Validate confidence
          val validConfidence = math.max(0.0, math.min(1.0, verdict.confidence))
          
          Some(verdict.copy(
            predicate = validPredicate,
            confidence = validConfidence
          ))
        case Left(_) =>
          // Fallback: try regex extraction
          extractVerdictRegex(response, candidate)
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to parse LLM verdict: ${e.getMessage}")
        extractVerdictRegex(response, candidate)
    }
  }
  
  /**
   * Fallback: Extract verdict using regex patterns
   */
  private def extractVerdictRegex(response: String, candidate: RelationCandidate): Option[LlmVerdict] = {
    val predicate = PredicatePattern.findFirstMatchIn(response.toLowerCase)
      .map(_.group("predicate"))
      .filter(predicateSet.contains)
      .getOrElse("related_to")
    
    val confidence = ConfidencePattern.findFirstMatchIn(response.toLowerCase)
      .map(_.group("confidence").toDouble)
      .getOrElse(0.5)
      .max(0.0).min(1.0)
    
    val evidence = EvidencePattern.findFirstMatchIn(response)
      .map(_.group("evidence"))
      .getOrElse(candidate.evidence.take(100))
    
    val ref = s"${candidate.a.lemma}_${predicate}_${candidate.b.lemma}"
    
    Some(LlmVerdict(
      predicate = predicate,
      confidence = confidence,
      evidence = evidence,
      ref = ref
    ))
  }
  
  /**
   * Check if a verdict meets the minimum confidence threshold
   */
  def meetsThreshold(verdict: LlmVerdict): Boolean = {
    verdict.confidence >= minConfidence
  }
}

