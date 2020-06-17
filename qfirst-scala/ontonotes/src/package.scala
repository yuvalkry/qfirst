package qfirst

import shapeless.{::, HNil}

package object ontonotes {
  import jjm.ling._
  type CoNLLToken = Index :: Pos :: Token :: HNil
  object CoNLLToken {
    def apply(index: Int, pos: String, token: String): CoNLLToken = {
      Index.field(index) :: Pos.field(pos) :: Token.field(token) :: HNil
    }
  }

  // TODO maybe can replace this with auto-derivations from shapeless? not sure
  import io.circe.{Encoder, Decoder}
  import io.circe.Json
  import io.circe.syntax._
  import jjm.implicits._

  implicit val conllTokenEncoder: Encoder[CoNLLToken] = {
    Encoder.instance[CoNLLToken](t =>
      Json.obj(
        "index" := t.index,
        "pos" := t.pos,
        "token" := t.token
      )
    )
  }
  implicit val conllTokenDecoder: Decoder[CoNLLToken] = {
    Decoder.instance(c =>
      for {
        index <- c.downField("index").as[Int]
        pos <- c.downField("pos").as[String]
        token <- c.downField("token").as[String]
      } yield CoNLLToken(index = index, pos = pos, token = token)
    )
  }

  // import jjm._
  // def memoizeDotKleisliIO[F[_], A <: Dot](
  //   dotKleisli: DotKleisli[IO, A],
  //   shouldCache: A => Boolean
  // ): DotKleisli[F, A] = {
  //   var cache = DotMap.empty[F, A]
  //   new DotKleisli[F, A] {
  //     def apply(a: A): F[a.Out] = {
  //       if(shouldCache(a)) {
  //         cache.get(a).map(IO.pure).getOrElse {
  //           dotKleisli(a).flatTap(res =>
  //             IO(cache = cache.put(a)(res))
  //           )
  //         }
  //       } else dotKleisli(a)
  //     }
  //   }
  // }
}
