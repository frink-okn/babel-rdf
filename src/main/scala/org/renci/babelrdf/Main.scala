package org.renci.babelrdf

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.system.StreamRDFWriter
import scopt.OParser

import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

final case class Config(
    prefixMaps: Seq[String] = Seq.empty,
    output: String = "-",
    inputs: Seq[String] = Seq.empty,
    quiet: Boolean = false
)

object Main:
  private val builder = OParser.builder[Config]
  private val parser =
    import builder.*
    OParser.sequence(
      programName("babel-rdf"),
      head("babel-rdf", "0.3.1"),
      help("help").abbr("h").text("show this help message"),
      version("version").text("show the version"),
      opt[String]("prefix-map")
        .required()
        .unbounded()
        .valueName("<prefix-map.json>")
        .action((value, config) => config.copy(prefixMaps = config.prefixMaps :+ value))
        .text("JSON prefix map; repeat to overlay additional prefixes"),
      opt[String]('o', "output")
        .valueName("<output.nt[.gz]>")
        .action((value, config) => config.copy(output = value))
        .text("output path, or - for stdout (default: -)"),
      opt[Unit]("quiet")
        .action((_, config) => config.copy(quiet = true))
        .text("do not print conversion counts"),
      arg[String]("<compendium.txt[.gz]>...")
        .unbounded()
        .optional()
        .action((value, config) => config.copy(inputs = config.inputs :+ value))
        .text("compendium JSONL inputs; omit or use - to read stdin"),
      checkConfig { config =>
        val inputs = if config.inputs.isEmpty then Seq("-") else config.inputs
        if inputs.count(_ == "-") > 1 then failure("stdin may only be specified once")
        else success
      }
    )

  def main(args: Array[String]): Unit =
    val exitCode = OParser.parse(parser, args, Config()) match
      case None =>
        if args.exists(arg => arg == "--help" || arg == "-h" || arg == "--version") then 0
        else 2
      case Some(config) =>
        try
          run(config)
          0
        catch
          case NonFatal(error) =>
            System.err.println(s"babel-rdf: ${error.getMessage}")
            1

    if exitCode != 0 then sys.exit(exitCode)

  private def run(config: Config): Unit =
    val mapper = new ObjectMapper()
    val prefixPaths = config.prefixMaps.map(Path.of(_))
    val prefixes = PrefixExpander.load(prefixPaths, mapper)
    val converter = new CompendiumConverter(prefixes, mapper)
    val inputs = if config.inputs.isEmpty then Seq("-") else config.inputs
    validateDistinctOutput(config.output, inputs, prefixPaths)
    val output = Io.openOutput(config.output)
    val rdf = StreamRDFWriter.getWriterStream(output.stream, RDFFormat.NTRIPLES_UTF8)
    var total = ConversionStats()

    try
      rdf.start()
      inputs.foreach { inputName =>
        val input = Io.openInput(inputName)
        try total += converter.convert(input.stream, inputName, rdf)
        finally input.close()
      }
      rdf.finish()
      output.commit()
    catch
      case error: Throwable =>
        try output.abort()
        catch case abortError: Throwable => error.addSuppressed(abortError)
        throw error

    if !config.quiet then
      System.err.println(s"Converted ${total.records} records to ${total.triples} triples")

  private def validateDistinctOutput(outputName: String, inputs: Seq[String], prefixMaps: Seq[Path]): Unit =
    if outputName != "-" then
      val output = Path.of(outputName).toAbsolutePath.normalize()
      val protectedPaths = inputs.filterNot(_ == "-").map(Path.of(_)) ++ prefixMaps

      protectedPaths.foreach { protectedPath =>
        val candidate = protectedPath.toAbsolutePath.normalize()
        val sameNormalizedPath = output == candidate
        val sameExistingFile =
          Files.exists(output) && Files.exists(candidate) && Files.isSameFile(output, candidate)

        if sameNormalizedPath || sameExistingFile then
          throw ConversionException(
            s"output '$outputName' must not refer to input or prefix-map file '$protectedPath'"
          )
      }
