package org.renci.babelrdf

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.riot.system.StreamRDF

import java.io.{IOException, InputStream}
import scala.jdk.CollectionConverters.*

final case class ConversionException(message: String, cause: Throwable | Null = null)
    extends RuntimeException(message, cause)

final case class ConversionStats(records: Long = 0, triples: Long = 0):
  def +(other: ConversionStats): ConversionStats =
    ConversionStats(records + other.records, triples + other.triples)

final class NodeConverter(prefixes: PrefixExpander, mapper: ObjectMapper):
  private val exactMatch = iri("skos:exactMatch", "RDF predicate")
  private val category = iri("biolink:category", "RDF predicate")

  def convert(input: InputStream, sourceName: String, output: StreamRDF): ConversionStats =
    val records = mapper.readerFor(classOf[JsonNode]).readValues[JsonNode](input)
    var recordNumber = 0L
    var tripleCount = 0L

    try
      var finished = false
      while !finished do
        val row = readNext(records, sourceName, recordNumber + 1)
        row match
          case None => finished = true
          case Some(value) =>
            recordNumber += 1
            try tripleCount += convertRow(value, sourceName, recordNumber, output)
            catch
              case error: ConversionException => throw error
              case error: Exception =>
                throw ConversionException(
                  s"$sourceName record $recordNumber: could not emit RDF: ${error.getMessage}",
                  error
                )
    finally records.close()

    ConversionStats(recordNumber, tripleCount)

  private def readNext(
      records: com.fasterxml.jackson.databind.MappingIterator[JsonNode],
      sourceName: String,
      recordNumber: Long
  ): Option[JsonNode] =
    try
      if records.hasNextValue then Some(records.nextValue()) else None
    catch
      case error: JsonProcessingException =>
        throw ConversionException(
          s"$sourceName record $recordNumber: malformed JSON: ${error.getOriginalMessage}",
          error
        )
      case error: IOException =>
        throw ConversionException(
          s"$sourceName record $recordNumber: could not read input: ${error.getMessage}",
          error
        )

  private def convertRow(
      row: JsonNode,
      sourceName: String,
      recordNumber: Long,
      output: StreamRDF
  ): Long =
    val context = s"$sourceName record $recordNumber"
    if !row.isObject then throw ConversionException(s"$context: expected a JSON object")

    if row.has("subject") || row.has("predicate") || row.has("object") then
      throw ConversionException(s"$context: edge records are not supported; provide a nodes file")

    val mainId = iri(requiredText(row, "id", context), s"$context field 'id'")
    val equivalentIdentifiers = requiredArray(row, "equivalent_identifiers", context)
    val categories = textValues(row, "category", context)

    var count = 0L
    equivalentIdentifiers.zipWithIndex.foreach { (identifier, index) =>
      val equivalent = iri(identifier, s"$context field 'equivalent_identifiers[$index]'")
      output.triple(Triple.create(equivalent, exactMatch, mainId))
      count += 1
    }

    categories.zipWithIndex.foreach { (categoryValue, index) =>
      val suffix = if categories.size == 1 then "" else s"[$index]"
      val categoryIri = iri(categoryValue, s"$context field 'category$suffix'")
      output.triple(Triple.create(mainId, category, categoryIri))
      count += 1
    }

    count

  private def requiredText(row: JsonNode, field: String, context: String): String =
    val value = row.get(field)
    if value == null || !value.isTextual || value.textValue().isBlank then
      throw ConversionException(s"$context: field '$field' must be a non-empty string")
    value.textValue()

  private def requiredArray(row: JsonNode, field: String, context: String): Seq[String] =
    val value = row.get(field)
    if value == null || !value.isArray then
      throw ConversionException(s"$context: field '$field' must be an array of strings")

    value.elements().asScala.zipWithIndex.map { (element, index) =>
      if !element.isTextual || element.textValue().isBlank then
        throw ConversionException(s"$context: field '$field[$index]' must be a non-empty string")
      element.textValue()
    }.toSeq

  private def textValues(row: JsonNode, field: String, context: String): Seq[String] =
    val value = row.get(field)
    if value == null then
      throw ConversionException(s"$context: field '$field' is required")
    else if value.isTextual && !value.textValue().isBlank then Seq(value.textValue())
    else if value.isArray then
      value.elements().asScala.zipWithIndex.map { (element, index) =>
        if !element.isTextual || element.textValue().isBlank then
          throw ConversionException(s"$context: field '$field[$index]' must be a non-empty string")
        element.textValue()
      }.toSeq
    else throw ConversionException(s"$context: field '$field' must be a non-empty string or array of strings")

  private def iri(value: String, context: String): Node =
    NodeFactory.createURI(prefixes.expand(value, context))
