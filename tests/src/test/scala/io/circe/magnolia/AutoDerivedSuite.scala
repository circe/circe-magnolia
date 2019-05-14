package io.circe.magnolia

import io.circe.magnolia.derivation.decoder.auto._
import io.circe.magnolia.derivation.encoder.auto._
import io.circe.testing.CodecTests
import io.circe.tests.CirceSuite
import io.circe.tests.examples._
import io.circe.{Decoder, Encoder, Json}
import shapeless.tag
import shapeless.tag.@@
import shapeless.test.illTyped

class AutoDerivedSuite extends CirceSuite {

  import AutoDerivedSuiteInputs._

  // TODO: All these imports are temporary workaround for https://github.com/propensive/magnolia/issues/89
  import Encoder._
  import Decoder._

  private implicit val encodeStringTag: Encoder[String @@ Tag] = Encoder[String].narrow
  private implicit val decodeStringTag: Decoder[String @@ Tag] = Decoder[String].map(tag[Tag](_))

  checkLaws("Codec[Tuple1[Int]]", CodecTests[Tuple1[Int]].unserializableCodec)
  checkLaws("Codec[(Int, Int, Foo)]", CodecTests[(Int, Int, Foo)].unserializableCodec)
  checkLaws("Codec[Qux[Int]]", CodecTests[Qux[Int]].unserializableCodec)
  checkLaws("Codec[Seq[Foo]]", CodecTests[Seq[Foo]].unserializableCodec)
  checkLaws("Codec[Baz]", CodecTests[Baz].unserializableCodec)
  checkLaws("Codec[Foo]", CodecTests[Foo].unserializableCodec)
  checkLaws("Codec[OuterCaseClassExample]", CodecTests[OuterCaseClassExample].unserializableCodec)
  checkLaws("Codec[RecursiveAdtExample]", CodecTests[RecursiveAdtExample].unserializableCodec)
  checkLaws("Codec[RecursiveWithOptionExample]", CodecTests[RecursiveWithOptionExample].unserializableCodec)
  checkLaws("Codec[RecursiveWithListExample]", CodecTests[RecursiveWithListExample].unserializableCodec)
  checkLaws("Codec[AnyValInside]", CodecTests[AnyValInside].unserializableCodec)
  checkLaws("Codec[AnyValWithJsonVal]", CodecTests[AnyValWithJsonValInside].unserializableCodec)

  "A generically derived codec" should "not interfere with base instances" in forAll { (is: List[Int]) =>
    val json = Encoder[List[Int]].apply(is)

    assert(json === Json.fromValues(is.map(Json.fromInt)) && json.as[List[Int]] === Right(is))
  }

  it should "not be derived for Object" in {
    illTyped("Decoder[Object]")
    illTyped("Encoder[Object]")
  }

  it should "not be derived for AnyRef" in {
    illTyped("Decoder[AnyRef]")
    illTyped("Encoder[AnyRef]")
  }

  "Generic decoders" should "not interfere with defined decoders" in forAll { (xs: List[String]) =>
    val json = Json.obj("SubtypeWithExplicitInstance" -> Json.fromValues(xs.map(Json.fromString)))
    val ch = Decoder[Sealed].apply(json.hcursor)
    val res = ch === Right(SubtypeWithExplicitInstance(xs): Sealed)
    assert(res)
  }

  "Generic encoders" should "not interfere with defined encoders" in forAll { (xs: List[String]) =>
    val json = Json.obj("SubtypeWithExplicitInstance" -> Json.fromValues(xs.map(Json.fromString)))

    assert(Encoder[Sealed].apply(SubtypeWithExplicitInstance(xs): Sealed) === json)
  }

  "JsonVal" should "encode as a single unwrapped value" in forAll { (jsonVal: AnyValWithJsonValInside) =>
    val json = Json.obj("id" -> Json.fromInt(jsonVal.id.value))

    val encoded = Encoder[AnyValWithJsonValInside].apply(jsonVal)

    assert(encoded === json)
  }

  "JsonVal" should "decode as a single unwrapped value to a value class" in forAll {
    (jsonVal: AnyValWithJsonValInside) =>
    val json = Json.obj("id" -> Json.fromInt(jsonVal.id.value))

    val decoded = Decoder[AnyValWithJsonValInside].decodeJson(json)
    assert(decoded === Right(jsonVal))
  }

  "JsonVal encoder" should "throw when the case class is a not a value class" in forAll {
    (jsonVal: ProductWithJsonVal) =>
      assertThrows[DerivationError](Encoder[ProductWithJsonVal].apply(jsonVal))
  }

  // TODO: tagged types don't work ATM, might be related to https://github.com/propensive/magnolia/issues/89
  //  checkLaws("Codec[WithTaggedMembers]", CodecTests[WithTaggedMembers].unserializableCodec)
  checkLaws("Codec[Seq[WithSeqOfTagged]]", CodecTests[Seq[WithSeqOfTagged]].unserializableCodec)
}
