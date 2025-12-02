package graphrag.ingestion

import graphrag.core.{Chunk, Concept, Mentions}
import org.slf4j.LoggerFactory

import java.security.MessageDigest
import java.util.Properties
import scala.collection.JavaConverters._
import scala.util.matching.Regex
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.ling.CoreAnnotations.{NamedEntityTagAnnotation, PartOfSpeechAnnotation, TextAnnotation, SentencesAnnotation, TokensAnnotation}
import edu.stanford.nlp.util.CoreMap

/**
 * Concept extraction from text chunks
 * 
 * This implements heuristic-based concept extraction.
 * For production, you would use Stanford CoreNLP or similar NLP libraries.
 * 
 * Extraction strategies:
 * 1. Capitalized words (potential named entities)
 * 2. Technical terms (CamelCase, acronyms)
 * 3. Common domain keywords
 */
object ConceptExtractor {
  
  private val logger = LoggerFactory.getLogger(getClass)
  
  // Stanford CoreNLP pipeline (lazy initialization - expensive to create)
  private lazy val nlpPipeline: StanfordCoreNLP = {
    logger.info("Initializing Stanford CoreNLP pipeline...")
    val props = new Properties()
    // Use tokenize, ssplit, pos, lemma, ner (named entity recognition)
    props.setProperty("annotators", "tokenize,ssplit,pos,lemma,ner")
    props.setProperty("tokenize.language", "en")
    // Memory-saving options
    props.setProperty("ner.useSUTime", "false")
    props.setProperty("ner.applyNumericClassifiers", "false")
    props.setProperty("ner.buildEntityMentions", "false") // Don't build entity mentions (saves memory)
    val pipeline = new StanfordCoreNLP(props)
    logger.info("Stanford CoreNLP pipeline initialized")
    pipeline
  }
  
  // Regex patterns for concept extraction
  private val CapitalizedWord: Regex = """\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\b""".r
  private val CamelCase: Regex = """\b[A-Z][a-z]+(?:[A-Z][a-z]+)+\b""".r
  private val Acronym: Regex = """\b[A-Z]{2,}\b""".r
  private val TechnicalTerm: Regex = """\b[a-z]+(?:[A-Z][a-z]+)+\b""".r
  
  // Stop words to filter out (common words that aren't concepts)
  private val StopWords = Set(
    "The", "This", "That", "These", "Those", "They", "There", "Then",
    "When", "Where", "What", "Which", "Who", "Why", "How",
    "Figure", "Table", "Section", "Chapter", "Page",
    "For", "From", "With", "Without", "About"
  )
  
  // POS tags for important nouns (NN = noun, NNP = proper noun, NNS = plural noun, NNPS = plural proper noun)
  private val NounTags = Set("NN", "NNP", "NNS", "NNPS")
  
  /**
   * Extract concepts from a chunk using heuristic rules
   * Returns a sequence of Mentions (chunk-concept pairs)
   */
  def extractHeuristic(chunk: Chunk): Seq[Mentions] = {
    val concepts = scala.collection.mutable.LinkedHashSet[Concept]()
    
    // Strategy 1: Capitalized words (potential named entities)
    CapitalizedWord.findAllIn(chunk.text).foreach { surface =>
      if (!StopWords.contains(surface) && surface.length > 2) {
        val concept = createConcept(surface, "NER")
        concepts += concept
      }
    }
    
    // Strategy 2: CamelCase words (e.g., GraphRAG, DataStream)
    CamelCase.findAllIn(chunk.text).foreach { surface =>
      val concept = createConcept(surface, "camelCase")
      concepts += concept
    }
    
    // Strategy 3: Acronyms (e.g., API, REST, LLM)
    Acronym.findAllIn(chunk.text).foreach { surface =>
      if (surface.length >= 2 && surface.length <= 6) {
        val concept = createConcept(surface, "acronym")
        concepts += concept
      }
    }
    
    // Strategy 4: Technical terms (e.g., dataStream, graphRAG)
    TechnicalTerm.findAllIn(chunk.text).foreach { surface =>
      val concept = createConcept(surface, "technicalTerm")
      concepts += concept
    }
    
    // Convert concepts to Mentions
    concepts.toSeq.map { concept =>
      Mentions(chunkId = chunk.chunkId, concept = concept)
    }
  }
  
  /**
   * Create a Concept from a surface form
   * 
   * @param surface The surface form (as it appears in text)
   * @param origin The extraction method (NER, keyphrase, etc.)
   * @return A Concept with generated ID and normalized lemma
   */
  private def createConcept(surface: String, origin: String): Concept = {
    val lemma = normalizeLemma(surface)
    val conceptId = generateConceptId(lemma)
    
    Concept(
      conceptId = conceptId,
      lemma = lemma,
      surface = surface,
      origin = origin
    )
  }
  
  /**
   * Normalize a surface form to a lemma
   * - Convert to lowercase
   * - Remove special characters
   * - Handle CamelCase splitting
   */
  private def normalizeLemma(surface: String): String = {
    // Split CamelCase into words
    val words = surface.replaceAll("([a-z])([A-Z])", "$1_$2")
    
    // Normalize to lowercase and clean
    words.toLowerCase
      .replaceAll("[^a-z0-9_]", "_")
      .replaceAll("_+", "_")
      .stripPrefix("_")
      .stripSuffix("_")
  }
  
  /**
   * Generate a stable concept ID from a lemma
   * Uses SHA-256 hash for deterministic IDs
   */
  private def generateConceptId(lemma: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(lemma.getBytes("UTF-8"))
    hash.map("%02x".format(_)).mkString.take(16) // Take first 16 chars of hash
  }
  
  /**
   * Extract concepts using Stanford CoreNLP for advanced NLP-based extraction
   * Uses Named Entity Recognition (NER) and Part-of-Speech (POS) tagging
   * 
   * @param chunk The chunk to extract concepts from
   * @return Sequence of Mentions (chunk-concept pairs)
   */
  def extractWithNLP(chunk: Chunk): Seq[Mentions] = {
    try {
      val concepts = scala.collection.mutable.LinkedHashSet[Concept]()
      
      // Create annotation from chunk text
      val annotation = new Annotation(chunk.text)
      nlpPipeline.annotate(annotation)
      
      // Extract sentences
      val sentences = annotation.get(classOf[SentencesAnnotation]).asScala
      
      for (sentence <- sentences) {
        val tokens = sentence.get(classOf[TokensAnnotation]).asScala
        
        // Track multi-word named entities
        var currentNER: Option[String] = None
        var currentNERText: StringBuilder = new StringBuilder()
        var currentNERType: Option[String] = None
        
        for (token <- tokens) {
          val word = token.get(classOf[TextAnnotation])
          val pos = token.get(classOf[PartOfSpeechAnnotation])
          val ner = token.get(classOf[NamedEntityTagAnnotation])
          
          // Strategy 1: Named Entity Recognition (PERSON, ORGANIZATION, LOCATION, etc.)
          if (ner != null && ner != "O") {
            // Check if this continues a multi-word entity
            if (currentNER.isDefined && currentNER.get == ner) {
              // Continue building the entity
              currentNERText.append(" ").append(word)
            } else {
              // Save previous entity if exists
              if (currentNER.isDefined && currentNERText.nonEmpty) {
                val surface = currentNERText.toString().trim
                if (surface.length > 2 && !StopWords.contains(surface)) {
                  val concept = createConcept(surface, s"NER_${currentNERType.get}")
                  concepts += concept
                }
              }
              // Start new entity
              currentNER = Some(ner)
              currentNERType = Some(ner)
              currentNERText = new StringBuilder(word)
            }
          } else {
            // Save previous entity if exists
            if (currentNER.isDefined && currentNERText.nonEmpty) {
              val surface = currentNERText.toString().trim
              if (surface.length > 2 && !StopWords.contains(surface)) {
                val concept = createConcept(surface, s"NER_${currentNERType.get}")
                concepts += concept
              }
            }
            currentNER = None
            currentNERText = new StringBuilder()
            currentNERType = None
            
            // Strategy 2: Important nouns (NN, NNP, NNS, NNPS)
            if (NounTags.contains(pos) && word.length > 2) {
              val lowerWord = word.toLowerCase
              if (!StopWords.contains(word) && !lowerWord.matches("^[0-9]+$")) {
                val concept = createConcept(word, s"POS_$pos")
                concepts += concept
              }
            }
          }
        }
        
        // Save any remaining entity
        if (currentNER.isDefined && currentNERText.nonEmpty) {
          val surface = currentNERText.toString().trim
          if (surface.length > 2 && !StopWords.contains(surface)) {
            val concept = createConcept(surface, s"NER_${currentNERType.get}")
            concepts += concept
          }
        }
      }
      
      // Also include heuristic extraction for technical terms (CamelCase, acronyms)
      // that NLP might miss
      val heuristicConcepts = extractHeuristic(chunk)
      heuristicConcepts.foreach { mention =>
        // Only add if not already found by NLP
        val existing = concepts.exists(_.lemma == mention.concept.lemma)
        if (!existing && (mention.concept.origin == "camelCase" || mention.concept.origin == "acronym")) {
          concepts += mention.concept
        }
      }
      
      // Convert concepts to Mentions
      concepts.toSeq.map { concept =>
        Mentions(chunkId = chunk.chunkId, concept = concept)
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Error in NLP extraction for chunk ${chunk.chunkId}, falling back to heuristic: ${e.getMessage}")
        // Fall back to heuristic extraction on error
        extractHeuristic(chunk)
    }
  }
}









