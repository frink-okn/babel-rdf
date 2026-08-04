package org.renci.babelrdf

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.{JsonNode, MappingIterator, ObjectMapper}
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.riot.system.StreamRDF

import java.io.{IOException, InputStream}
import scala.jdk.CollectionConverters.*

final case class ConversionException(message: String, cause: Throwable | Null = null)
    extends RuntimeException(message, cause)

final case class ConversionStats(records: Long = 0, triples: Long = 0):
  def +(other: ConversionStats): ConversionStats =
    ConversionStats(records + other.records, triples + other.triples)

final class CompendiumConverter(prefixes: PrefixExpander, mapper: ObjectMapper):
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
      records: MappingIterator[JsonNode],
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

    val identifiers = requiredIdentifiers(row, context)
    val leader = iri(identifiers.head, s"$context field 'identifiers[0].i'")
    val categoryValue = requiredText(row, "type", context)

    var count = 0L
    identifiers.zipWithIndex.foreach { (identifier, index) =>
      val equivalent = iri(identifier, s"$context field 'identifiers[$index].i'")
      output.triple(Triple.create(equivalent, exactMatch, leader))
      count += 1
    }

    val categoryIri = iri(categoryValue, s"$context field 'type'")
    output.triple(Triple.create(leader, category, categoryIri))
    count + 1

  private def requiredIdentifiers(row: JsonNode, context: String): Seq[String] =
    val value = row.get("identifiers")
    if value == null || !value.isArray || value.isEmpty then
      throw ConversionException(s"$context: field 'identifiers' must be a non-empty array")

    value.elements().asScala.zipWithIndex.map { (element, index) =>
      if !element.isObject then
        throw ConversionException(s"$context: field 'identifiers[$index]' must be an object")
      requiredText(element, "i", s"$context field 'identifiers[$index]'")
    }.toSeq

  private def requiredText(row: JsonNode, field: String, context: String): String =
    val value = row.get(field)
    if value == null || !value.isTextual || value.textValue().isBlank then
      throw ConversionException(s"$context: field '$field' must be a non-empty string")
    value.textValue()

  private def iri(value: String, context: String): Node =
    NodeFactory.createURI(prefixes.expand(value, context))
