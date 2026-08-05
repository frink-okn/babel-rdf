package org.renci.babelrdf

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class MainSuite extends FunSuite:
  test("returns a nonzero status for invalid command-line usage"):
    val result = runMain()
    assertEquals(result.exitCode, 2)
    assert(result.stderr.contains("Missing option --prefix-map"))

  test("returns zero for help"):
    val result = runMain("--help")
    assertEquals(result.exitCode, 0)
    assert(result.stdout.contains("Usage: babel-rdf"))

  test("refuses to overwrite an aliased input path"):
    val directory = Files.createTempDirectory("babel-rdf-alias-test-")
    val prefixMap = writePrefixMap(directory)
    val input = directory.resolve("compendium.txt")
    val original =
      """{"type":"biolink:Disease","identifiers":[{"i":"MONDO:1","l":"one"}]}
        |""".stripMargin
    Files.writeString(input, original)

    val result = runMain(
      "--prefix-map",
      prefixMap.toString,
      "--output",
      input.toString,
      input.toString
    )

    assertEquals(result.exitCode, 1)
    assert(result.stderr.contains("must not refer to input or prefix-map file"))
    assertEquals(Files.readString(input), original)

  test("preserves an existing output when conversion fails"):
    val directory = Files.createTempDirectory("babel-rdf-atomic-test-")
    val prefixMap = writePrefixMap(directory)
    val input = directory.resolve("compendium.txt")
    val output = directory.resolve("output.nt")
    Files.writeString(
      input,
      """{"type":"biolink:Disease","identifiers":[{"i":"UNKNOWN:1","l":"unknown"}]}"""
    )
    Files.writeString(output, "previous output\n")

    val result = runMain(
      "--prefix-map",
      prefixMap.toString,
      "--output",
      output.toString,
      input.toString
    )

    assertEquals(result.exitCode, 1)
    assert(result.stderr.contains("unknown prefix 'UNKNOWN'"))
    assertEquals(Files.readString(output), "previous output\n")

  private def writePrefixMap(directory: Path): Path =
    val path = directory.resolve("prefix-map.json")
    Files.writeString(
      path,
      """{"MONDO":"http://purl.obolibrary.org/obo/MONDO_","biolink":"https://w3id.org/biolink/vocab/","skos":"http://www.w3.org/2004/02/skos/core#","rdfs":"http://www.w3.org/2000/01/rdf-schema#"}"""
    )
    path

  private def runMain(arguments: String*): ProcessResult =
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val command = Seq(
      java,
      "-cp",
      System.getProperty("java.class.path"),
      "org.renci.babelrdf.Main"
    ) ++ arguments
    val process = new ProcessBuilder(command.asJava).start()
    val stdout = String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val stderr = String(process.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
    ProcessResult(process.waitFor(), stdout, stderr)

  private final case class ProcessResult(exitCode: Int, stdout: String, stderr: String)
