package com.unboundaistudio

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/**
 * Qwen2 ByteLevel BPE tokenizer for the converted qwen2_encoder.onnx graph.
 * Reads the exact Qwen/Qwen2-0.5B tokenizer.json bundled during GitHub build.
 * Never substitute a generic LLM tokenizer: wrong IDs corrupt video prompts.
 */
internal class Qwen2MotionTokenizer private constructor(
 private val vocab: JSONObject,
 private val merges: Map<String, Int>,
) {
 companion object {
  @Volatile private var cached: Qwen2MotionTokenizer? = null
  private const val PAD = 151643L

  fun fromAssets(context: Context): Qwen2MotionTokenizer =
   cached ?: synchronized(this) {
    cached ?: run {
     val json = context.assets.open("qwen2_tokenizer.json").bufferedReader().use { it.readText() }
     val model = JSONObject(json).getJSONObject("model")
     val vocab = model.getJSONObject("vocab")
     val rawMerges = model.getJSONArray("merges")
     val ranks = HashMap<String, Int>(rawMerges.length())
     for (i in 0 until rawMerges.length()) {
      val item = rawMerges.get(i)
      val pair = if (item is org.json.JSONArray) {
       item.getString(0) + " " + item.getString(1)
      } else item.toString()
      ranks[pair] = i
     }
     Qwen2MotionTokenizer(vocab, ranks).also { cached = it }
    }
   }
 }

 private val byteMap: Array<String> = run {
  val allowed = BooleanArray(256)
  for (b in 33..126) allowed[b] = true
  for (b in 161..172) allowed[b] = true
  for (b in 174..255) allowed[b] = true
  var extra = 0
  Array(256) { b ->
   val codepoint = if (allowed[b]) b else 256 + extra++
   codepoint.toChar().toString()
  }
 }

 private val splitRegex = Regex(
  """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""
 )

 fun encode(prompt: String, size: Int = 77): Pair<LongArray, LongArray> {
  require(size in 1..300) { "Invalid Qwen2 token length" }
  val tokens = mutableListOf<Long>()
  val normalized = Normalizer.normalize(prompt, Normalizer.Form.NFC)
  for (match in splitRegex.findAll(normalized)) {
   val symbols = match.value.toByteArray(Charsets.UTF_8)
    .map { b -> byteMap[b.toInt() and 0xff] }.toMutableList()
   while (symbols.size > 1) {
    var bestIndex = -1
    var bestRank = Int.MAX_VALUE
    for (i in 0 until symbols.lastIndex) {
     val rank = merges[symbols[i] + " " + symbols[i + 1]]
     if (rank != null && rank < bestRank) { bestRank = rank; bestIndex = i }
    }
    if (bestIndex < 0) break
    symbols[bestIndex] += symbols[bestIndex + 1]
    symbols.removeAt(bestIndex + 1)
   }
   for (symbol in symbols) {
    val id = vocab.optLong(symbol, -1)
    require(id >= 0) { "Qwen2 tokenizer missing vocabulary entry" }
    tokens.add(id)
    if (tokens.size >= size) break
   }
   if (tokens.size >= size) break
  }
  require(tokens.isNotEmpty()) { "Write a movement prompt" }
  val ids = LongArray(size) { index -> if (index < tokens.size) tokens[index] else PAD }
  val mask = LongArray(size) { index -> if (index < tokens.size) 1L else 0L }
  return ids to mask
 }
}
