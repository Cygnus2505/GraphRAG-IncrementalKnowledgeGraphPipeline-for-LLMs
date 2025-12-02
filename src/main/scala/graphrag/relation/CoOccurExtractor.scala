package graphrag.relation

import graphrag.core.{Chunk, Concept, CoOccur, Mentions}
import org.slf4j.LoggerFactory
import com.typesafe.config.ConfigFactory

/**
 * Co-occurrence extraction from mentions
 * 
 * Groups mentions by chunkId and finds concept pairs that appear together.
 * Calculates frequencies and filters by PMI (Pointwise Mutual Information).
 */
object CoOccurExtractor {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private val appConfig = ConfigFactory.load()
  
  private val windowSize = appConfig.getInt("relation.cooccur.window")
  private val minPMI = appConfig.getDouble("relation.cooccur.min-pmi")
  
  /**
   * Extract co-occurrences from a sequence of mentions for a single chunk
   * 
   * @param mentions All mentions from a chunk
   * @param chunkId The chunk ID (used as windowId)
   * @return Sequence of CoOccur objects representing concept pairs
   */
  def extractCoOccurrences(mentions: Seq[Mentions], chunkId: String): Seq[CoOccur] = {
    if (mentions.length < 2) {
      return Seq.empty
    }
    
    val concepts = mentions.map(_.concept).distinct
    val coOccurs = scala.collection.mutable.ListBuffer[CoOccur]()
    
    // Generate all pairs of concepts within the chunk
    for (i <- concepts.indices) {
      for (j <- (i + 1) until concepts.length) {
        val a = concepts(i)
        val b = concepts(j)
        
        // Skip self-pairs and ensure consistent ordering (lexicographic by conceptId)
        val (conceptA, conceptB) = if (a.conceptId < b.conceptId) (a, b) else (b, a)
        
        val coOccur = CoOccur(
          a = conceptA,
          b = conceptB,
          windowId = chunkId,
          freq = 1L
        )
        
        coOccurs += coOccur
      }
    }
    
    coOccurs.toSeq
  }
  
  /**
   * Calculate PMI (Pointwise Mutual Information) for a co-occurrence
   * PMI(a, b) = log2(P(a, b) / (P(a) * P(b)))
   * 
   * @param coOccurFreq Frequency of co-occurrence
   * @param conceptAFreq Frequency of concept A across all chunks
   * @param conceptBFreq Frequency of concept B across all chunks
   * @param totalChunks Total number of chunks
   * @return PMI value
   */
  def calculatePMI(
    coOccurFreq: Long,
    conceptAFreq: Long,
    conceptBFreq: Long,
    totalChunks: Long
  ): Double = {
    if (totalChunks == 0 || conceptAFreq == 0 || conceptBFreq == 0 || coOccurFreq == 0) {
      return 0.0
    }
    
    val pAB = coOccurFreq.toDouble / totalChunks
    val pA = conceptAFreq.toDouble / totalChunks
    val pB = conceptBFreq.toDouble / totalChunks
    
    if (pA == 0.0 || pB == 0.0 || pAB == 0.0) {
      return 0.0
    }
    
    val pmi = math.log(pAB / (pA * pB)) / math.log(2.0) // log base 2
    pmi
  }
  
  /**
   * Filter co-occurrences by PMI threshold
   * 
   * @param coOccurs Co-occurrences with frequencies
   * @param conceptFreqs Map of concept ID to frequency
   * @param totalChunks Total number of chunks
   * @return Filtered co-occurrences with PMI >= minPMI
   */
  def filterByPMI(
    coOccurs: Seq[CoOccur],
    conceptFreqs: Map[String, Long],
    totalChunks: Long
  ): Seq[CoOccur] = {
    coOccurs.filter { coOccur =>
      val conceptAFreq = conceptFreqs.getOrElse(coOccur.a.conceptId, 0L)
      val conceptBFreq = conceptFreqs.getOrElse(coOccur.b.conceptId, 0L)
      
      val pmi = calculatePMI(
        coOccurFreq = coOccur.freq,
        conceptAFreq = conceptAFreq,
        conceptBFreq = conceptBFreq,
        totalChunks = totalChunks
      )
      
      pmi >= minPMI
    }
  }
  
  /**
   * Aggregate co-occurrence frequencies across multiple chunks
   * 
   * @param coOccurs Sequence of co-occurrences (may have duplicates)
   * @return Aggregated co-occurrences with summed frequencies
   */
  def aggregateFrequencies(coOccurs: Seq[CoOccur]): Seq[CoOccur] = {
    val grouped = coOccurs.groupBy { coOccur =>
      // Use concept IDs as key for grouping
      (coOccur.a.conceptId, coOccur.b.conceptId)
    }
    
    grouped.values.map { group =>
      val first = group.head
      val totalFreq = group.map(_.freq).sum
      first.copy(freq = totalFreq)
    }.toSeq
  }
}




















