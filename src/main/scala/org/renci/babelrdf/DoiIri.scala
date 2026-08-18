package org.renci.babelrdf

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

private[babelrdf] object DoiIri:
  private val Hex = "0123456789ABCDEF"
  private val SafePathPunctuation = "-._~!$&'()*+,;=:@/"

  def encodeReference(reference: String): Either[String, String] =
    if reference.isEmpty then Left("DOI reference is empty")
    else
      var index = 0
      var needsEncoding = false

      while index < reference.length do
        if isPercentEscape(reference, index) then
          val end = percentEscapeRunEnd(reference, index)
          validatePercentEscapeRun(reference, index, end) match
            case Some(reason) => return Left(reason)
            case None => index = end
        else
          val codePoint = reference.codePointAt(index)
          if isWhitespaceOrControl(codePoint) then
            return Left(
              f"DOI reference contains whitespace or control character U+$codePoint%04X at index $index"
            )
          if !isSafePathCodePoint(codePoint) then needsEncoding = true
          index += Character.charCount(codePoint)

      if !needsEncoding then Right(reference)
      else Right(encodeUnsafeCodePoints(reference))

  private def encodeUnsafeCodePoints(reference: String): String =
    val encoded = new java.lang.StringBuilder(reference.length + 16)
    var index = 0

    while index < reference.length do
      if isPercentEscape(reference, index) then
        encoded.append(reference, index, index + 3)
        index += 3
      else
        val codePoint = reference.codePointAt(index)
        if isSafePathCodePoint(codePoint) then encoded.appendCodePoint(codePoint)
        else appendPercentEncoded(encoded, codePoint)
        index += Character.charCount(codePoint)

    encoded.toString

  private def appendPercentEncoded(encoded: java.lang.StringBuilder, codePoint: Int): Unit =
    val bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)
    bytes.foreach { byte =>
      val unsigned = byte & 0xff
      encoded.append('%')
      encoded.append(Hex.charAt(unsigned >>> 4))
      encoded.append(Hex.charAt(unsigned & 0x0f))
    }

  private def isPercentEscape(value: String, index: Int): Boolean =
    index + 2 < value.length &&
      value.charAt(index) == '%' &&
      isHexDigit(value.charAt(index + 1)) &&
      isHexDigit(value.charAt(index + 2))

  private def percentEscapeRunEnd(value: String, start: Int): Int =
    var end = start
    while isPercentEscape(value, end) do end += 3
    end

  private def validatePercentEscapeRun(value: String, start: Int, end: Int): Option[String] =
    val bytes = new Array[Byte]((end - start) / 3)
    var index = start
    var byteIndex = 0

    while index < end do
      val octet = Integer.parseInt(value.substring(index + 1, index + 3), 16)
      if octet <= 0x20 || octet == 0x7f then
        return Some(
          f"DOI reference contains percent-encoded whitespace or control byte 0x$octet%02X at index $index"
        )
      bytes(byteIndex) = octet.toByte
      byteIndex += 1
      index += 3

    decodeUtf8(bytes) match
      case None => Some(s"DOI reference contains malformed percent-encoded UTF-8 at index $start")
      case Some(decoded) =>
        var decodedIndex = 0
        var invalid: Option[String] = None
        while decodedIndex < decoded.length && invalid.isEmpty do
          val codePoint = decoded.codePointAt(decodedIndex)
          if isWhitespaceOrControl(codePoint) then
            invalid = Some(
              f"DOI reference contains percent-encoded whitespace or control character U+$codePoint%04X at index $start"
            )
          decodedIndex += Character.charCount(codePoint)
        invalid

  private def decodeUtf8(bytes: Array[Byte]): Option[String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Some(decoder.decode(ByteBuffer.wrap(bytes)).toString)
    catch case _: CharacterCodingException => None

  private def isHexDigit(value: Char): Boolean =
    value >= '0' && value <= '9' ||
      value >= 'A' && value <= 'F' ||
      value >= 'a' && value <= 'f'

  private def isWhitespaceOrControl(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) ||
      Character.isSpaceChar(codePoint) ||
      Character.isISOControl(codePoint)

  private def isSafePathCodePoint(codePoint: Int): Boolean =
    codePoint < 128 &&
      (Character.isLetterOrDigit(codePoint) || SafePathPunctuation.indexOf(codePoint) >= 0)
