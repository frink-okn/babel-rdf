package org.renci.babelrdf

import munit.FunSuite

class DoiIriSuite extends FunSuite:
  test("encodes characters that are unsafe in a DOI resolver path"):
    val reference = "10.1002/(SICI)1097-4652(199605)167:2<333::AID-JCP18>3.0.CO;2-#?"

    assertEquals(
      DoiIri.encodeReference(reference),
      Right(
        "10.1002/(SICI)1097-4652(199605)167:2%3C333::AID-JCP18%3E3.0.CO;2-%23%3F"
      )
    )

  test("preserves existing percent escapes without double encoding"):
    val reference = "10.1002/(SICI)1097-0061(199605)12:6%3C609::AID-YEA949%3E3.0.CO;2-B"

    assertEquals(DoiIri.encodeReference(reference), Right(reference))

  test("encodes a literal percent sign when it is not an escape"):
    assertEquals(DoiIri.encodeReference("10.1000/foo%zz"), Right("10.1000/foo%25zz"))

  test("rejects raw and ASCII percent-encoded whitespace"):
    assert(DoiIri.encodeReference("10.1000/foo bar").isLeft)
    assert(DoiIri.encodeReference("10.1000/foo%20bar").isLeft)

  test("rejects percent-encoded Unicode whitespace and controls"):
    assert(DoiIri.encodeReference("10.1000/foo%C2%A0bar").isLeft)
    assert(DoiIri.encodeReference("10.1000/foo%E2%80%83bar").isLeft)
    assert(DoiIri.encodeReference("10.1000/foo%C2%85bar").isLeft)

  test("preserves percent-encoded non-whitespace Unicode"):
    val reference = "10.1000/caf%C3%A9"

    assertEquals(DoiIri.encodeReference(reference), Right(reference))

  test("rejects malformed percent-encoded UTF-8"):
    assert(DoiIri.encodeReference("10.1000/foo%FFbar").isLeft)
    assert(DoiIri.encodeReference("10.1000/foo%C2%A0%FFbar").isLeft)
