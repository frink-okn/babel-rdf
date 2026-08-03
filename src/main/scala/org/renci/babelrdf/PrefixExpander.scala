package org.renci.babelrdf

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final class PrefixExpander private (prefixes: Map[String, String]):
  def expand(value: String, context: String): String =
    val colon = value.indexOf(':')
    if colon <= 0 then
      throw ConversionException(s"$context: expected a CURIE or absolute IRI, got '$value'")

    val prefix = value.substring(0, colon)
    val reference = value.substring(colon + 1)

    prefixes.get(prefix) match
      case Some(base) => base + reference
      case None if value.startsWith("http://") || value.startsWith("https://") || value.startsWith("urn:") =>
        value
      case None =>
        throw ConversionException(s"$context: unknown prefix '$prefix' in '$value'")

object PrefixExpander:
  def load(paths: Seq[Path], mapper: ObjectMapper): PrefixExpander =
    if paths.isEmpty then throw ConversionException("at least one prefix map is required")

    val prefixes = paths.foldLeft(Map.empty[String, String]) { (acc, path) =>
      val root =
        try mapper.readTree(Files.newInputStream(path))
        catch
          case error: Exception =>
            throw ConversionException(s"could not read prefix map '$path': ${error.getMessage}", error)

      if !root.isObject then
        throw ConversionException(s"prefix map '$path' must be a JSON object")

      val entries = root.properties().asScala.map { entry =>
        if !entry.getValue.isTextual then
          throw ConversionException(s"prefix map '$path' has a non-string value for '${entry.getKey}'")
        entry.getKey -> entry.getValue.textValue()
      }.toMap

      acc ++ entries
    }

    new PrefixExpander(prefixes)

  private[babelrdf] def fromMap(prefixes: Map[String, String]): PrefixExpander =
    new PrefixExpander(prefixes)
