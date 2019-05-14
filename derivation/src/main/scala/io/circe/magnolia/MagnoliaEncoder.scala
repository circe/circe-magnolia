package io.circe.magnolia

import io.circe.magnolia.configured.Configuration
import io.circe.{Encoder, Json}
import magnolia._

private[magnolia] object MagnoliaEncoder {

  private[magnolia] def combine[T](caseClass: CaseClass[Encoder, T])(implicit config: Configuration): Encoder[T] = {
    val paramJsonValLookup = caseClass.annotations.collectFirst {
      case ann: JsonVal => ann
    }
    val paramJsonKeyLookup = caseClass.parameters.map { p =>
      val jsonKeyAnnotation = p.annotations.collectFirst {
        case ann: JsonKey => ann
      }
      jsonKeyAnnotation match {
        case Some(ann) => p.label -> ann.value
        case None => p.label -> config.transformMemberNames(p.label)
      }
    }.toMap

    if (paramJsonValLookup.isDefined && !caseClass.isValueClass) {
      throw new DerivationError(
        "JsonVal is only supported on value classes"
      )
    }

    if (paramJsonKeyLookup.values.toList.distinct.size != caseClass.parameters.length) {
      throw new DerivationError(
        "Duplicate key detected after applying transformation function for case class parameters"
      )
    }

    paramJsonValLookup match {
      case Some(_) => new Encoder[T] {
        def apply(a: T): Json = {
          val p = caseClass.parameters.head
          p.typeclass(p.dereference(a))
        }
      }
      case None => new Encoder[T] {
        def apply(a: T): Json =
          Json.obj(caseClass.parameters.map { p =>
            val label = paramJsonKeyLookup.getOrElse(p.label,
              throw new IllegalStateException(
                "Looking up a parameter label should always yield a value. This is a bug"))
            label -> p.typeclass(p.dereference(a))
          }: _*)
      }
    }

  }

  private[magnolia] def dispatch[T](
    sealedTrait: SealedTrait[Encoder, T]
  )(implicit config: Configuration): Encoder[T] = {
    {
      val origTypeNames = sealedTrait.subtypes.map(_.typeName.short)
      val transformed = origTypeNames.map(config.transformConstructorNames).distinct
      if (transformed.length != origTypeNames.length) {
        throw new DerivationError(
          "Duplicate key detected after applying transformation function for " +
            "sealed trait child classes"
        )
      }
    }

    new Encoder[T] {
      def apply(a: T): Json = {
        sealedTrait.dispatch(a) { subtype =>
          val baseJson = subtype.typeclass(subtype.cast(a))
          val constructorName = config
            .transformConstructorNames(subtype.typeName.short)
          config.discriminator match {
            case Some(discriminator) => {
              // Note: Here we handle the edge case where a subtype of a sealed trait has a custom encoder which does not encode
              // encode into a JSON object and thus we cannot insert the discriminator key. In this case we fallback
              // to the non-discriminator case for this subtype. This is same as the behavior of circe-generic-extras
              baseJson.asObject match {
                case Some(jsObj) => Json.fromJsonObject(jsObj.add(discriminator, Json.fromString(constructorName)))
                case None => Json.obj(constructorName -> baseJson)
              }
            }
            case None =>
              Json.obj(constructorName -> baseJson)
          }
        }
      }
    }
  }

}
