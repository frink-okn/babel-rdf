package org.renci.babelrdf

import java.io.*
import java.nio.file.{Files, Path}
import java.nio.file.StandardCopyOption.{ATOMIC_MOVE, REPLACE_EXISTING}
import java.nio.file.AtomicMoveNotSupportedException
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

final case class InputHandle(stream: InputStream, close: () => Unit)

final class OutputHandle private[babelrdf] (
    val stream: OutputStream,
    closeAction: () => Unit,
    commitAction: () => Unit,
    abortAction: () => Unit
):
  private var closed = false
  private var completed = false

  def commit(): Unit =
    if !completed then
      closeOnce()
      commitAction()
      completed = true

  def abort(): Unit =
    if !completed then
      var failure: Throwable | Null = null
      try closeOnce()
      catch case error: Throwable => failure = error

      try abortAction()
      catch
        case error: Throwable if failure != null => failure.addSuppressed(error)
        case error: Throwable => failure = error

      completed = true
      if failure != null then throw failure

  private def closeOnce(): Unit =
    if !closed then
      closed = true
      closeAction()

object Io:
  private val BufferSize = 1024 * 1024

  def openInput(name: String): InputHandle =
    val isStdin = name == "-"
    val raw = if isStdin then System.in else Files.newInputStream(Path.of(name))
    val buffered = new BufferedInputStream(raw, BufferSize)
    buffered.mark(2)
    val first = buffered.read()
    val second = buffered.read()
    buffered.reset()

    val stream =
      if first == 0x1f && second == 0x8b then new GZIPInputStream(buffered, BufferSize)
      else buffered

    val close =
      if isStdin then () => ()
      else () => stream.close()
    InputHandle(stream, close)

  def openOutput(name: String): OutputHandle =
    val isStdout = name == "-"
    if isStdout then
      val stream = new BufferedOutputStream(System.out, BufferSize)
      new OutputHandle(stream, () => stream.flush(), () => (), () => ())
    else
      val target = Path.of(name).toAbsolutePath.normalize()
      val parent = Option(target.getParent).getOrElse(Path.of(".").toAbsolutePath.normalize())
      Files.createDirectories(parent)
      val compressed = name.endsWith(".gz")
      val suffix = if compressed then ".tmp.gz" else ".tmp"
      val temporary = Files.createTempFile(parent, ".babel-rdf-", suffix)

      try
        val raw = Files.newOutputStream(temporary)
        val buffered = new BufferedOutputStream(raw, BufferSize)
        val stream =
          if compressed then new GZIPOutputStream(buffered, BufferSize)
          else buffered

        new OutputHandle(
          stream,
          () => stream.close(),
          () => replace(temporary, target),
          () => Files.deleteIfExists(temporary)
        )
      catch
        case error: Throwable =>
          Files.deleteIfExists(temporary)
          throw error

  private def replace(source: Path, target: Path): Unit =
    try Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    catch
      case _: AtomicMoveNotSupportedException => Files.move(source, target, REPLACE_EXISTING)
