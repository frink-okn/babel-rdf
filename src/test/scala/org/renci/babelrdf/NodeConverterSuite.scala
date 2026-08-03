package org.renci.babelrdf

import com.fasterxml.jackson.databind.ObjectMapper
import munit.FunSuite
import org.apache.jena.graph.Triple
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.system.{StreamRDFBase, StreamRDFWriter}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

class NodeConverterSuite extends FunSuite:
  private val prefixes = PrefixExpander.fromMap(
    Map(
      "MONDO" -> "http://purl.obolibrary.org/obo/MONDO_",
      "DOID" -> "http://purl.obolibrary.org/obo/DOID_",
      "OMIM" -> "http://purl.obolibrary.org/obo/OMIM_",
      "UMLS" -> "http://identifiers.org/umls/",
      "medgen" -> "https://www.ncbi.nlm.nih.gov/medgen/",
      "biolink" -> "https://w3id.org/biolink/vocab/",
      "skos" -> "http://www.w3.org/2004/02/skos/core#"
    )
  )

  test("writes exact matches toward the main ID and a category triple"):
    val json =
      """{"id":"MONDO:0033486","name":"leukodystrophy, hypomyelinating, 14","category":"biolink:Disease","equivalent_identifiers":["MONDO:0033486","DOID:0080296","OMIM:617899","UMLS:C4693535","medgen:1635255"]}"""

    val (result, stats) = convert(json.getBytes(StandardCharsets.UTF_8))
    val lines = result.linesIterator.toSet

    assertEquals(stats, ConversionStats(records = 1, triples = 6))
    assertEquals(
      lines,
      Set(
        "<http://purl.obolibrary.org/obo/MONDO_0033486> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<http://purl.obolibrary.org/obo/DOID_0080296> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<http://purl.obolibrary.org/obo/OMIM_617899> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<http://identifiers.org/umls/C4693535> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<https://www.ncbi.nlm.nih.gov/medgen/1635255> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<http://purl.obolibrary.org/obo/MONDO_0033486> <https://w3id.org/biolink/vocab/category> <https://w3id.org/biolink/vocab/Disease> ."
      )
    )

  test("reads concatenated JSON objects without retaining the corpus"):
    val json =
      """{"id":"MONDO:1","category":"biolink:Disease","equivalent_identifiers":["MONDO:1"]}
        |{"id":"MONDO:2","category":"biolink:Disease","equivalent_identifiers":["MONDO:2"]}
        |""".stripMargin

    val (_, stats) = convert(json.getBytes(StandardCharsets.UTF_8))
    assertEquals(stats, ConversionStats(records = 2, triples = 4))

  test("reports an unknown prefix with record context"):
    val json =
      """{"id":"MISSING:1","category":"biolink:Disease","equivalent_identifiers":["MISSING:1"]}"""

    val error = intercept[ConversionException] {
      convert(json.getBytes(StandardCharsets.UTF_8))
    }
    assert(error.getMessage.contains("test.jsonl record 1 field 'id': unknown prefix 'MISSING'"))

  test("rejects edge records"):
    val json =
      """{"id":"edge-1","subject":"MONDO:1","predicate":"biolink:same_as","object":"MONDO:2"}"""

    val error = intercept[ConversionException] {
      convert(json.getBytes(StandardCharsets.UTF_8))
    }
    assert(error.getMessage.contains("edge records are not supported"))

  test("attributes output failures to the current record"):
    val json =
      """{"id":"MONDO:1","category":"biolink:Disease","equivalent_identifiers":["MONDO:1"]}"""
    val failingOutput = new StreamRDFBase:
      override def triple(triple: Triple): Unit = throw new IOException("disk full")
    val converter = new NodeConverter(prefixes, new ObjectMapper())

    val error = intercept[ConversionException] {
      converter.convert(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
        "test.jsonl",
        failingOutput
      )
    }

    assertEquals(error.getMessage, "test.jsonl record 1: could not emit RDF: disk full")
    assert(error.getCause.isInstanceOf[IOException])

  private def convert(bytes: Array[Byte]): (String, ConversionStats) =
    val output = new ByteArrayOutputStream()
    val rdf = StreamRDFWriter.getWriterStream(output, RDFFormat.NTRIPLES_UTF8)
    val converter = new NodeConverter(prefixes, new ObjectMapper())
    rdf.start()
    val stats = converter.convert(new ByteArrayInputStream(bytes), "test.jsonl", rdf)
    rdf.finish()
    (output.toString(StandardCharsets.UTF_8), stats)
