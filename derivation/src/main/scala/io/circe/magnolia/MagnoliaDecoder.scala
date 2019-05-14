package io.circe.magnolia

import cats.syntax.either._
import io.circe.{Decoder, DecodingFailure, HCursor}
import io.circe.Decoder.Result
import io.circe.magnolia.configured.Configuration
import magnolia._
import mercator._

private[magnolia] object MagnoliaDecoder {

  private[magnolia] def combine[T](
    caseClass: CaseClass[Decoder, T]
  )(implicit configuration: Configuration): Decoder[T] = {
    val paramJsonValLookup = caseClass.annotations.collectFirst {
      case ann: JsonVal if caseClass.isValueClass => ann
    }
    val paramJsonKeyLookup: Map[String, String] = caseClass.parameters.map{ p =>
      val jsonKeyAnnotation = p.annotations.collectFirst {
        case ann: JsonKey => ann
      }
      jsonKeyAnnotation match {
        case Some(ann) => p.label -> ann.value
        case None => p.label -> configuration.transformMemberNames(p.label)
      }
    }.toMap

    if (paramJsonKeyLookup.values.toList.distinct.length != caseClass.parameters.length) {
      throw new DerivationError(
        "Duplicate key detected after applying transformation function for case class parameters"
      )
    }

    paramJsonValLookup match {
      case Some(_) => new Decoder[T] {
        override def apply(c: HCursor): Result[T] = {
          caseClass.constructMonadic { p =>
            p.typeclass.tryDecode(c)
          }
        }
      }
      case None =>
        if (configuration.useDefaults) {
          new Decoder[T] {
            override def apply(c: HCursor): Result[T] = {
              caseClass.constructMonadic { p =>
                val key = paramJsonKeyLookup.getOrElse(p.label,
                  throw new IllegalStateException(
                    "Looking up a parameter label should always yield a value. This is a bug")
                )
                val keyCursor = c.downField(key)
                keyCursor.focus match {
                  case Some(json) => json.as[p.PType](p.typeclass)
                  case None => p.default.fold {
                    // Some decoders (in particular,
                    // the default Option[T] decoder) do special things when a key is missing,
                    // so we give them a chance to do their thing here.
                    p.typeclass.tryDecode(keyCursor)
                  }(Right(_))
                }
              }
            }
          }
        }
        else {
          new Decoder[T] {
            def apply(c: HCursor): Result[T] = {
              caseClass.constructMonadic { p =>
                p.typeclass.tryDecode(c.downField(paramJsonKeyLookup.getOrElse(p.label, throw new IllegalStateException("Looking up a parameter label should always yield a value. This is a bug"))))
              }
            }
          }
        }
    }
  }

  private[magnolia] def dispatch[T](
    sealedTrait: SealedTrait[Decoder, T]
  )(implicit configuration: Configuration): Decoder[T] = {

    val constructorLookup: Map[String, Subtype[Decoder, T]] =
      sealedTrait.subtypes.map { s =>
        configuration.transformConstructorNames(s.typeName.short) -> s
      }.toMap

    if (constructorLookup.size != sealedTrait.subtypes.length) {
      throw new DerivationError("Duplicate key detected after applying transformation function for case class parameters")
    }

    configuration.discriminator match {
      case Some(discriminator) => new DiscriminatedDecoder[T](discriminator, constructorLookup)
      case None => new NonDiscriminatedDecoder[T](constructorLookup)
    }
  }

  private[magnolia] class NonDiscriminatedDecoder[T](constructorLookup: Map[String, Subtype[Decoder, T]]) extends Decoder[T] {
    private val knownSubTypes = constructorLookup.keys.toSeq.sorted.mkString(",")

    override def apply(c: HCursor): Result[T] = {
      c.keys match {
            case Some(keys) if keys.size == 1 =>
              val key = keys.head
              for {
                theSubtype <- Either.fromOption(
                  constructorLookup.get(key),
                  DecodingFailure(
                    s"""Can't decode coproduct type: couldn't find matching subtype.
                       |JSON: ${c.value},
                       |Key: $key
                       |Known subtypes: $knownSubTypes\n""".stripMargin,
                    c.history
                  )
                )

                result <- c.get(key)(theSubtype.typeclass)
              } yield result
            case _ =>
              Left(
                DecodingFailure(
                  s"""Can't decode coproduct type: zero or several keys were found, while coproduct type requires exactly one.
                   |JSON: ${c.value},
                   |Keys: ${c.keys.map(_.mkString(","))}
                   |Known subtypes: $knownSubTypes\n""".stripMargin,
                  c.history
                )
              )
          }
    }
  }

  private[magnolia] class DiscriminatedDecoder[T](discriminator: String, constructorLookup: Map[String, Subtype[Decoder, T]]) extends Decoder[T] {
    val knownSubTypes = constructorLookup.keys.toSeq.sorted.mkString(",")

    override def apply(c: HCursor): Result[T] = {
      c.downField(discriminator).as[String] match {
        case Left(_) => Left(DecodingFailure(
          s"""
             |Can't decode coproduct type: couldn't find discriminator or is not of type String.
             |JSON: ${c.value}
             |discriminator key: discriminator
              """.stripMargin,
          c.history
        ))
        case Right(ctorName) => constructorLookup.get(ctorName) match {
          case Some(subType) => subType.typeclass.apply(c)
          case None => Left(DecodingFailure(
            s"""
               |Can't decode coproduct type: constructor name not found in known constructor names
               |JSON: ${c.value}
               |Allowed discriminators: $knownSubTypes
              """.stripMargin,
            c.history
          ))
        }
      }
    }

  }
}

