package org.renci.babelrdf

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.{JsonNode, MappingIterator, ObjectMapper}
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.irix.{IRIException, IRIx}
import org.apache.jena.riot.system.StreamRDF

import java.io.{IOException, InputStream}
import scala.jdk.CollectionConverters.*

final case class ConversionException(message: String, cause: Throwable | Null = null)
    extends RuntimeException(message, cause)

final case class ConversionStats(
    records: Long = 0,
    triples: Long = 0,
    invalidIdentifiers: Long = 0,
    droppedCliques: Long = 0
):
  def +(other: ConversionStats): ConversionStats =
    ConversionStats(
      records + other.records,
      triples + other.triples,
      invalidIdentifiers + other.invalidIdentifiers,
      droppedCliques + other.droppedCliques
    )

enum InvalidIriPolicy:
  case Filter, Fail

private final case class Identifier(id: String, label: Option[String])
private final case class ResolvedIdentifier(identifier: Identifier, node: Node)
private final case class InvalidIri(reason: String, cause: Throwable | Null = null)
private final case class RowStats(
    triples: Long = 0,
    invalidIdentifiers: Long = 0,
    droppedCliques: Long = 0
)

final class CompendiumConverter(
    prefixes: PrefixExpander,
    mapper: ObjectMapper,
    invalidIriPolicy: InvalidIriPolicy = InvalidIriPolicy.Filter,
    reportWarning: String => Unit = message => System.err.println(s"babel-rdf: warning: $message")
):
  private val exactMatch = requiredIri("skos:exactMatch", "RDF predicate")
  private val category = requiredIri("biolink:category", "RDF predicate")
  private val label = requiredIri("rdfs:label", "RDF predicate")

  def convert(input: InputStream, sourceName: String, output: StreamRDF): ConversionStats =
    val records = mapper.readerFor(classOf[JsonNode]).readValues[JsonNode](input)
    var recordNumber = 0L
    var tripleCount = 0L
    var invalidIdentifierCount = 0L
    var droppedCliqueCount = 0L

    try
      var finished = false
      while !finished do
        val row = readNext(records, sourceName, recordNumber + 1)
        row match
          case None => finished = true
          case Some(value) =>
            recordNumber += 1
            try
              val rowStats = convertRow(value, sourceName, recordNumber, output)
              tripleCount += rowStats.triples
              invalidIdentifierCount += rowStats.invalidIdentifiers
              droppedCliqueCount += rowStats.droppedCliques
            catch
              case error: ConversionException => throw error
              case error: Exception =>
                throw ConversionException(
                  s"$sourceName record $recordNumber: could not emit RDF: ${error.getMessage}",
                  error
                )
    finally records.close()

    ConversionStats(recordNumber, tripleCount, invalidIdentifierCount, droppedCliqueCount)

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
  ): RowStats =
    val context = s"$sourceName record $recordNumber"
    if !row.isObject then throw ConversionException(s"$context: expected a JSON object")

    val identifiers = requiredIdentifiers(row, context)
    val categoryValue = requiredText(row, "type", context)
    val leaderContext = s"$context field 'identifiers[0].i'"

    val leader = identifierIri(identifiers.head.id, leaderContext, "dropping clique") match
      case Some(node) => node
      case None => return RowStats(invalidIdentifiers = 1, droppedCliques = 1)

    val categoryIri = requiredIri(categoryValue, s"$context field 'type'")
    val resolved = Seq.newBuilder[ResolvedIdentifier]
    resolved += ResolvedIdentifier(identifiers.head, leader)
    var invalidIdentifierCount = 0L

    identifiers.tail.zipWithIndex.foreach { (identifier, offset) =>
      val index = offset + 1
      val identifierContext = s"$context field 'identifiers[$index].i'"
      identifierIri(identifier.id, identifierContext, "skipping identifier") match
        case Some(node) => resolved += ResolvedIdentifier(identifier, node)
        case None => invalidIdentifierCount += 1
    }

    var count = 0L
    resolved.result().foreach { resolvedIdentifier =>
      output.triple(Triple.create(resolvedIdentifier.node, exactMatch, leader))
      count += 1
      resolvedIdentifier.identifier.label.foreach { labelValue =>
        output.triple(
          Triple.create(resolvedIdentifier.node, label, NodeFactory.createLiteralString(labelValue))
        )
        count += 1
      }
    }

    output.triple(Triple.create(leader, category, categoryIri))
    RowStats(triples = count + 1, invalidIdentifiers = invalidIdentifierCount)

  private def requiredIdentifiers(row: JsonNode, context: String): Seq[Identifier] =
    val value = row.get("identifiers")
    if value == null || !value.isArray || value.isEmpty then
      throw ConversionException(s"$context: field 'identifiers' must be a non-empty array")

    value.elements().asScala.zipWithIndex.map { (element, index) =>
      if !element.isObject then
        throw ConversionException(s"$context: field 'identifiers[$index]' must be an object")
      val identifierContext = s"$context field 'identifiers[$index]'"
      Identifier(
        requiredText(element, "i", identifierContext),
        optionalString(element, "l")
      )
    }.toSeq

  private def requiredText(row: JsonNode, field: String, context: String): String =
    val value = row.get(field)
    if value == null || !value.isTextual || value.textValue().isBlank then
      throw ConversionException(s"$context: field '$field' must be a non-empty string")
    value.textValue()

  private def optionalString(row: JsonNode, field: String): Option[String] =
    Option(row.get(field)).filter(_.isTextual).map(_.textValue()).filter(_.nonEmpty)

  private def identifierIri(value: String, context: String, action: String): Option[Node] =
    validateIdentifierIri(value, context) match
      case Right(node) => Some(node)
      case Left(error) =>
        val message = s"$context: invalid identifier '$value': ${error.reason}"
        invalidIriPolicy match
          case InvalidIriPolicy.Fail => throw ConversionException(message, error.cause)
          case InvalidIriPolicy.Filter =>
            reportWarning(s"$message; $action")
            None

  private def validateIdentifierIri(value: String, context: String): Either[InvalidIri, Node] =
    val colon = value.indexOf(':')
    if colon > 0 && value.substring(0, colon) == "doi" then
      DoiIri.encodeReference(value.substring(colon + 1)) match
        case Left(reason) => Left(InvalidIri(reason))
        case Right(reference) => validateIri(s"doi:$reference", context)
    else validateIri(value, context)

  private def requiredIri(value: String, context: String): Node =
    validateIri(value, context) match
      case Right(node) => node
      case Left(error) =>
        throw ConversionException(s"$context: invalid IRI '$value': ${error.reason}", error.cause)

  private def validateIri(value: String, context: String): Either[InvalidIri, Node] =
    val expanded = prefixes.expand(value, context)
    try
      val parsed = IRIx.create(expanded)
      if Option(parsed.scheme()).forall(_.isEmpty) then
        Left(InvalidIri(s"expanded IRI '$expanded' is not absolute"))
      else Right(NodeFactory.createURI(expanded))
    catch
      case error: IRIException => Left(InvalidIri(error.getMessage, error))
