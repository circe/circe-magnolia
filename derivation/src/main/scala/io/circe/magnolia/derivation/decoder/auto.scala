package io.circe.magnolia.derivation.decoder

import io.circe.Decoder
import io.circe.magnolia.MagnoliaDecoder
import magnolia.{CaseClass, Magnolia, SealedTrait}
import scala.language.experimental.macros

object auto {

  type Typeclass[T] = Decoder[T]

  private[magnolia] def combine[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
    MagnoliaDecoder.combine(caseClass)

  private[magnolia] def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] =
    MagnoliaDecoder.dispatch(sealedTrait)

  implicit def magnoliaDecoder[T]: Typeclass[T] = macro Magnolia.gen[T]
}