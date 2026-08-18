package org.renci.babelrdf

import com.fasterxml.jackson.databind.ObjectMapper
import munit.FunSuite
import org.apache.jena.graph.Triple
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.system.{StreamRDFBase, StreamRDFWriter}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets

class CompendiumConverterSuite extends FunSuite:
  private val prefixes = PrefixExpander.fromMap(
    Map(
      "MONDO" -> "http://purl.obolibrary.org/obo/MONDO_",
      "DOID" -> "http://purl.obolibrary.org/obo/DOID_",
      "OMIM" -> "http://purl.obolibrary.org/obo/OMIM_",
      "UMLS" -> "http://identifiers.org/umls/",
      "UniProtKB" -> "http://purl.uniprot.org/uniprot/",
      "SNOMEDCT" -> "http://snomed.info/id/",
      "PMID" -> "http://www.ncbi.nlm.nih.gov/pubmed/",
      "doi" -> "https://doi.org/",
      "medgen" -> "https://www.ncbi.nlm.nih.gov/medgen/",
      "biolink" -> "https://w3id.org/biolink/vocab/",
      "skos" -> "http://www.w3.org/2004/02/skos/core#",
      "rdfs" -> "http://www.w3.org/2000/01/rdf-schema#"
    )
  )

  test("writes matches and labels for every member and types only the leader"):
    val json =
      """{"type":"biolink:Disease","identifiers":[{"i":"MONDO:0033486","l":"leukodystrophy"},{"i":"DOID:0080296","l":"hypomyelinating leukodystrophy 14"},{"i":"OMIM:617899","l":"Leukodystrophy, hypomyelinating, 14"},{"i":"UMLS:C4693535","l":"Hypomyelinating leukodystrophy 14"},{"i":"medgen:1635255","l":"Leukodystrophy, hypomyelinating, 14"}],"preferred_name":"leukodystrophy, hypomyelinating, 14"}"""

    val (result, stats) = convert(json.getBytes(StandardCharsets.UTF_8))
    val lines = result.linesIterator.toSet

    assertEquals(stats, ConversionStats(records = 1, triples = 11))
    assertEquals(
      lines,
      Set(
        "<http://purl.obolibrary.org/obo/MONDO_0033486> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<http://purl.obolibrary.org/obo/MONDO_0033486> <http://www.w3.org/2000/01/rdf-schema#label> \"leukodystrophy\" .",
        "<http://purl.obolibrary.org/obo/DOID_0080296> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<http://purl.obolibrary.org/obo/DOID_0080296> <http://www.w3.org/2000/01/rdf-schema#label> \"hypomyelinating leukodystrophy 14\" .",
        "<http://purl.obolibrary.org/obo/OMIM_617899> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<http://purl.obolibrary.org/obo/OMIM_617899> <http://www.w3.org/2000/01/rdf-schema#label> \"Leukodystrophy, hypomyelinating, 14\" .",
        "<http://identifiers.org/umls/C4693535> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<http://identifiers.org/umls/C4693535> <http://www.w3.org/2000/01/rdf-schema#label> \"Hypomyelinating leukodystrophy 14\" .",
        "<https://www.ncbi.nlm.nih.gov/medgen/1635255> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_0033486> .",
        "<https://www.ncbi.nlm.nih.gov/medgen/1635255> <http://www.w3.org/2000/01/rdf-schema#label> \"Leukodystrophy, hypomyelinating, 14\" .",
        "<http://purl.obolibrary.org/obo/MONDO_0033486> <https://w3id.org/biolink/vocab/category> <https://w3id.org/biolink/vocab/Disease> ."
      )
    )

  test("a singleton clique skips an empty label"):
    val json =
      """{"type":"biolink:Disease","identifiers":[{"i":"MONDO:1","l":""}]}"""

    val (result, stats) = convert(json.getBytes(StandardCharsets.UTF_8))
    assertEquals(stats, ConversionStats(records = 1, triples = 2))
    assertEquals(result.linesIterator.size, 2)
    assert(result.contains("<http://purl.obolibrary.org/obo/MONDO_1> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_1> ."))
    assert(!result.contains("http://www.w3.org/2000/01/rdf-schema#label"))

  test("reads concatenated compendium records without retaining the corpus"):
    val json =
      """{"type":"biolink:Disease","identifiers":[{"i":"MONDO:1","l":"one"}]}
        |{"type":"biolink:Disease","identifiers":[{"i":"MONDO:2","l":"two"},{"i":"DOID:2","l":"two"}]}
        |""".stripMargin

    val (_, stats) = convert(json.getBytes(StandardCharsets.UTF_8))
    assertEquals(stats, ConversionStats(records = 2, triples = 8))

  test("reports an unknown leader prefix with record context"):
    val json =
      """{"type":"biolink:Disease","identifiers":[{"i":"MISSING:1","l":"missing"}]}"""

    val error = intercept[ConversionException] {
      convert(json.getBytes(StandardCharsets.UTF_8))
    }
    assert(error.getMessage.contains("test.txt record 1 field 'identifiers[0].i': unknown prefix 'MISSING'"))

  test("rejects an empty identifiers list"):
    val json = """{"type":"biolink:Disease","identifiers":[]}"""

    val error = intercept[ConversionException] {
      convert(json.getBytes(StandardCharsets.UTF_8))
    }
    assert(error.getMessage.contains("field 'identifiers' must be a non-empty array"))

  test("skips missing and non-string labels"):
    val json =
      """{"type":"biolink:Disease","identifiers":[{"i":"MONDO:1"},{"i":"DOID:1","l":42}]}"""

    val (result, stats) = convert(json.getBytes(StandardCharsets.UTF_8))
    assertEquals(stats, ConversionStats(records = 1, triples = 3))
    assert(!result.contains("http://www.w3.org/2000/01/rdf-schema#label"))

  test("filters an invalid secondary identifier without changing the leader"):
    val json =
      """{"type":"biolink:Disease","identifiers":[{"i":"MONDO:1","l":"one"},{"i":"SNOMEDCT: 274897005","l":"bad"},{"i":"DOID:1","l":"valid"}]}"""

    val (result, stats, warnings) =
      convertWithDiagnostics(json.getBytes(StandardCharsets.UTF_8))

    assertEquals(
      stats,
      ConversionStats(records = 1, triples = 5, invalidIdentifiers = 1)
    )
    assert(!result.contains("snomed.info"))
    assert(result.contains("<http://purl.obolibrary.org/obo/DOID_1> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://purl.obolibrary.org/obo/MONDO_1> ."))
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("field 'identifiers[1].i'"))
    assert(warnings.head.contains("skipping identifier"))

  test("drops a clique whose leader is invalid without promoting another identifier"):
    val json =
      """{"type":"biolink:Protein","identifiers":[{"i":"UniProtKB:P0DTC1|P0DTD1"},{"i":"UMLS:C5575367","l":"nsp5"}]}
        |{"type":"biolink:Disease","identifiers":[{"i":"MONDO:1"}]}
        |""".stripMargin

    val (result, stats, warnings) =
      convertWithDiagnostics(json.getBytes(StandardCharsets.UTF_8))

    assertEquals(
      stats,
      ConversionStats(records = 2, triples = 2, invalidIdentifiers = 1, droppedCliques = 1)
    )
    assert(!result.contains("P0DTC1"))
    assert(!result.contains("C5575367"))
    assert(result.contains("MONDO_1"))
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("field 'identifiers[0].i'"))
    assert(warnings.head.contains("dropping clique"))

  test("strict invalid IRI handling fails instead of filtering"):
    val json =
      """{"type":"biolink:Disease","identifiers":[{"i":"MONDO:1"},{"i":"SNOMEDCT: 274897005"}]}"""
    val converter = new CompendiumConverter(
      prefixes,
      new ObjectMapper(),
      InvalidIriPolicy.Fail
    )

    val error = intercept[ConversionException] {
      converter.convert(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
        "test.txt",
        new StreamRDFBase()
      )
    }

    assert(error.getMessage.contains("field 'identifiers[1].i'"))
    assert(error.getMessage.contains("invalid identifier 'SNOMEDCT: 274897005'"))

  test("encodes legacy DOI references but still filters DOI whitespace"):
    val json =
      """{"type":"biolink:Publication","identifiers":[{"i":"PMID:1"},{"i":"doi:10.1002/1097-4679(195801)14:1<41::aid-jclp2270140112>3.0.co;2-h"},{"i":"doi:10.1000/bad value"}]}"""

    val (result, stats, warnings) =
      convertWithDiagnostics(json.getBytes(StandardCharsets.UTF_8))

    assertEquals(
      stats,
      ConversionStats(records = 1, triples = 3, invalidIdentifiers = 1)
    )
    assert(result.contains("https://doi.org/10.1002/1097-4679(195801)14:1%3C41::aid-jclp2270140112%3E3.0.co;2-h"))
    assert(!result.contains("bad"))
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("DOI reference contains whitespace"))

  test("attributes output failures to the current record"):
    val json =
      """{"type":"biolink:Disease","identifiers":[{"i":"MONDO:1","l":"one"}]}"""
    val failingOutput = new StreamRDFBase:
      override def triple(triple: Triple): Unit = throw new IOException("disk full")
    val converter = new CompendiumConverter(prefixes, new ObjectMapper())

    val error = intercept[ConversionException] {
      converter.convert(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
        "test.txt",
        failingOutput
      )
    }

    assertEquals(error.getMessage, "test.txt record 1: could not emit RDF: disk full")
    assert(error.getCause.isInstanceOf[IOException])

  private def convert(bytes: Array[Byte]): (String, ConversionStats) =
    val (result, stats, _) = convertWithDiagnostics(bytes)
    (result, stats)

  private def convertWithDiagnostics(
      bytes: Array[Byte]
  ): (String, ConversionStats, Seq[String]) =
    val output = new ByteArrayOutputStream()
    val rdf = StreamRDFWriter.getWriterStream(output, RDFFormat.NTRIPLES_UTF8)
    val warnings = Seq.newBuilder[String]
    val converter = new CompendiumConverter(
      prefixes,
      new ObjectMapper(),
      reportWarning = warning => warnings += warning
    )
    rdf.start()
    val stats = converter.convert(new ByteArrayInputStream(bytes), "test.txt", rdf)
    rdf.finish()
    (output.toString(StandardCharsets.UTF_8), stats, warnings.result())
