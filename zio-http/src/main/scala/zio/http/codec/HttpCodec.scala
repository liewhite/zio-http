/*
 * Copyright 2021 - 2023 Sporta Technologies PVT LTD & the ZIO HTTP contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.http.codec

import scala.language.implicitConversions

import zio._

import zio.stream.ZStream

import zio.schema.Schema

import zio.http._
import zio.http.model._

/**
 * A [[zio.http.codec.HttpCodec]] represents a codec for a part of an HTTP
 * request. HttpCodec the HTTP protocol, these parts may be the unconsumed
 * portion of the HTTP path (a route codec), the query string parameters (a
 * query codec), the request headers (a header codec), or the request body (a
 * body codec).
 *
 * A HttpCodec is a purely declarative description of an input, and therefore,
 * it can be used to generate documentation, clients, and client libraries.
 *
 * HttpCodecs are a bit like invertible multi-channel parsers.
 */
sealed trait HttpCodec[-AtomTypes, Value] {
  self =>
  private lazy val encoderDecoder = zio.http.codec.internal.EncoderDecoder(self)

  /**
   * Returns a new codec that is the same as this one, but has attached docs,
   * which will render whenever docs are generated from the codec.
   */
  final def ??(doc: Doc): HttpCodec[AtomTypes, Value] = HttpCodec.WithDoc(self, doc)

  final def |[AtomTypes1 <: AtomTypes, Value2](
    that: HttpCodec[AtomTypes1, Value2],
  )(implicit alternator: Alternator[Value, Value2]): HttpCodec[AtomTypes1, alternator.Out] =
    HttpCodec
      .Fallback(self, that)
      .transform[alternator.Out](
        either => either.fold(alternator.left(_), alternator.right(_)),
        value =>
          alternator
            .unleft(value)
            .map(Left(_))
            .orElse(alternator.unright(value).map(Right(_)))
            .get, // TODO: Solve with partiality
      )

  /**
   * Returns a new codec that is the composition of this codec and the specified
   * codec. For codecs that include route codecs, the routes will be decoded
   * sequentially from left to right.
   */
  final def ++[AtomTypes1 <: AtomTypes, Value2](that: HttpCodec[AtomTypes1, Value2])(implicit
    combiner: Combiner[Value, Value2],
  ): HttpCodec[AtomTypes1, combiner.Out] =
    HttpCodec.Combine[AtomTypes1, AtomTypes1, Value, Value2, combiner.Out](self, that, combiner)

  /**
   * Combines two query codecs into another query codec.
   */
  final def &[Value2](
    that: QueryCodec[Value2],
  )(implicit
    combiner: Combiner[Value, Value2],
    ev: HttpCodecType.Query <:< AtomTypes,
  ): QueryCodec[combiner.Out] =
    self.asQuery ++ that

  /**
   * Combines two route codecs into another route codec.
   */
  final def /[Value2](
    that: PathCodec[Value2],
  )(implicit
    combiner: Combiner[Value, Value2],
    ev: HttpCodecType.Path <:< AtomTypes,
  ): PathCodec[combiner.Out] =
    self.asRoute ++ that

  /**
   * Produces a flattened collection of alternatives. Once flattened, each codec
   * inside the returned collection is guaranteed to contain no nested
   * alternatives.
   */
  final def alternatives: Chunk[HttpCodec[AtomTypes, Value]] = HttpCodec.flattenFallbacks(self)

  /**
   * Reinterprets this codec as a query codec assuming evidence that this
   * interpretation is sound.
   */
  final def asQuery(implicit ev: HttpCodecType.Query <:< AtomTypes): QueryCodec[Value] =
    self.asInstanceOf[QueryCodec[Value]]

  /**
   * Reinterpets this codec as a route codec assuming evidence that this
   * interpretation is sound.
   */
  final def asRoute(implicit ev: HttpCodecType.Path <:< AtomTypes): PathCodec[Value] =
    self.asInstanceOf[PathCodec[Value]]

  /**
   * Transforms the type parameter to `Unit` by specifying that all possible
   * values that can be decoded from this `HttpCodec` are in fact equivalent to
   * the provided canonical value.
   *
   * Note: You should NOT use this method on any codec which can decode
   * semantically distinct values.
   */
  final def const(canonical: => Value): HttpCodec[AtomTypes, Unit] =
    self.transform(_ => (), _ => canonical)

  final def const[Value2](value2: => Value2)(implicit ev: Unit <:< Value): HttpCodec[AtomTypes, Value2] =
    self.transform(_ => value2, _ => ev(()))

  /**
   * Uses this codec to decode the Scala value from a request.
   */
  final def decodeRequest(request: Request)(implicit trace: Trace): Task[Value] =
    decode(request.url, Status.Ok, request.method, request.headers, request.body)

  /**
   * Uses this codec to decode the Scala value from a response.
   */
  final def decodeResponse(response: Response)(implicit trace: Trace): Task[Value] =
    decode(URL.empty, response.status, Method.GET, response.headers, response.body)

  private final def decode(url: URL, status: Status, method: Method, headers: Headers, body: Body)(implicit
    trace: Trace,
  ): Task[Value] =
    encoderDecoder.decode(url, status, method, headers, body)

  /**
   * Uses this codec to encode the Scala value into a request.
   */
  final def encodeRequest(value: Value): Request =
    encodeWith(value)((url, _, method, headers, body) =>
      Request(
        url = url,
        method = method.getOrElse(Method.GET),
        headers = headers,
        body = body,
        version = Version.Http_1_1,
        remoteAddress = None,
      ),
    )

  /**
   * Uses this codec to encode the Scala value as a patch to a request.
   */
  final def encodeRequestPatch(value: Value): Request.Patch =
    encodeWith(value)((url, _, _, headers, _) =>
      Request.Patch(
        addQueryParams = url.queryParams,
        addHeaders = headers,
      ),
    )

  /**
   * Uses this codec to encode the Scala value as a response.
   */
  final def encodeResponse[Z](value: Value): Response =
    encodeWith(value)((_, status, _, headers, body) =>
      Response(headers = headers, body = body, status = status.getOrElse(Status.Ok)),
    )

  /**
   * Uses this codec to encode the Scala value as a response patch.
   */
  final def encodeResponsePatch[Z](value: Value): Response.Patch =
    encodeWith(value)((_, status, _, headers, _) =>
      Response.Patch.addHeaders(headers) ++ status.map(Response.Patch.setStatus(_)).getOrElse(Response.Patch.empty),
    )

  private final def encodeWith[Z](value: Value)(
    f: (URL, Option[Status], Option[Method], Headers, Body) => Z,
  ): Z =
    encoderDecoder.encodeWith(value)(f)

  /**
   * Returns a new codec that will expect the value to be equal to the specified
   * value.
   */
  def expect(expected: Value): HttpCodec[AtomTypes, Unit] =
    transformOrFailLeft(
      actual =>
        if (actual == expected) Right(())
        else Left(s"Expected ${expected} but found ${actual}"),
      _ => expected,
    )

  /**
   * Returns a new codec, where the value produced by this one is optional.
   */
  final def optional: HttpCodec[AtomTypes, Option[Value]] =
    self
      .orElseEither(HttpCodec.empty)
      .transform[Option[Value]](_.swap.toOption, _.fold[Either[Unit, Value]](Left(()))(Right(_)).swap)

  final def orElseEither[AtomTypes1 <: AtomTypes, Value2](
    that: HttpCodec[AtomTypes1, Value2],
  )(implicit alternator: Alternator[Value, Value2]): HttpCodec[AtomTypes1, alternator.Out] =
    self | that

  final def toLeft[R]: HttpCodec[AtomTypes, Either[Value, R]] =
    self.transformOrFail[Either[Value, R]](
      value => Right(Left(value)),
      either => either.swap.left.map(_ => "Error!"),
    ) // TODO: Solve with partiality

  final def toRight[L]: HttpCodec[AtomTypes, Either[L, Value]] =
    self.transformOrFail[Either[L, Value]](
      value => Right(Right(value)),
      either => either.left.map(_ => "Error!"),
    ) // TODO: Solve with partiality

  /**
   * Transforms the type parameter of this HttpCodec from `Value` to `Value2`.
   * Due to the fact that HttpCodec is invariant in its type parameter, the
   * transformation requires not just a function from `Value` to `Value2`, but
   * also, a function from `Value2` to `Value`.
   *
   * One of these functions will be used in decoding, for example, when the
   * endpoint is invoked on the server. The other of these functions will be
   * used in encoding, for example, when a client calls the endpoint on the
   * server.
   */
  final def transform[Value2](f: Value => Value2, g: Value2 => Value): HttpCodec[AtomTypes, Value2] =
    HttpCodec.TransformOrFail[AtomTypes, Value, Value2](self, in => Right(f(in)), output => Right(g(output)))

  final def transformOrFail[Value2](
    f: Value => Either[String, Value2],
    g: Value2 => Either[String, Value],
  ): HttpCodec[AtomTypes, Value2] =
    HttpCodec.TransformOrFail[AtomTypes, Value, Value2](self, f, g)

  final def transformOrFailLeft[Value2](
    f: Value => Either[String, Value2],
    g: Value2 => Value,
  ): HttpCodec[AtomTypes, Value2] =
    HttpCodec.TransformOrFail[AtomTypes, Value, Value2](self, f, output => Right(g(output)))

  final def transformOrFailRight[Value2](
    f: Value => Value2,
    g: Value2 => Either[String, Value],
  ): HttpCodec[AtomTypes, Value2] =
    HttpCodec.TransformOrFail[AtomTypes, Value, Value2](self, in => Right(f(in)), g)
}

object HttpCodec
    extends ContentCodecs
    with HeaderCodecs
    with MethodCodecs
    with PathCodecs
    with QueryCodecs
    with StatusCodecs {
  implicit def stringToLiteral(string: String): PathCodec[Unit] = literal(string)

  private[http] sealed trait AtomTag
  private[http] object AtomTag {
    case object Status  extends AtomTag
    case object Path    extends AtomTag
    case object Content extends AtomTag
    case object Query   extends AtomTag
    case object Header  extends AtomTag
    case object Method  extends AtomTag
  }

  def empty: HttpCodec[Any, Unit] =
    Empty

  def unused: HttpCodec[Any, ZNothing] = Halt

  private[http] sealed trait Atom[-AtomTypes, Value0] extends HttpCodec[AtomTypes, Value0] {
    def tag: AtomTag

    def index: Int

    def withIndex(index: Int): Atom[AtomTypes, Value0]
  }

  private[http] final case class Status[A](codec: SimpleCodec[zio.http.model.Status, A], index: Int = 0)
      extends Atom[HttpCodecType.Status, A]                         {
    self =>
    def erase: Status[Any] = self.asInstanceOf[Status[Any]]

    def tag: AtomTag = AtomTag.Status

    def withIndex(index: Int): Status[A] = copy(index = index)
  }
  private[http] final case class Path[A](textCodec: TextCodec[A], name: Option[String], index: Int = 0)
      extends Atom[HttpCodecType.Path, A]                           { self =>
    def erase: Path[Any] = self.asInstanceOf[Path[Any]]

    def tag: AtomTag = AtomTag.Path

    def withIndex(index: Int): Path[A] = copy(index = index)
  }
  private[http] final case class Content[A](schema: Schema[A], index: Int = 0) extends Atom[HttpCodecType.Content, A] {
    self =>
    def tag: AtomTag = AtomTag.Content

    def withIndex(index: Int): Content[A] = copy(index = index)
  }
  private[http] final case class ContentStream[A](schema: Schema[A], index: Int = 0)
      extends Atom[HttpCodecType.Content, ZStream[Any, Nothing, A]] {
    def tag: AtomTag = AtomTag.Content

    def withIndex(index: Int): ContentStream[A] = copy(index = index)
  }
  private[http] final case class Query[A](name: String, textCodec: TextCodec[A], index: Int = 0)
      extends Atom[HttpCodecType.Query, A]                          {
    self =>
    def erase: Query[Any] = self.asInstanceOf[Query[Any]]

    def tag: AtomTag = AtomTag.Query

    def withIndex(index: Int): Query[A] = copy(index = index)
  }

  private[http] final case class Method[A](codec: SimpleCodec[zio.http.model.Method, A], index: Int = 0)
      extends Atom[HttpCodecType.Method, A] {
    self =>
    def erase: Method[Any] = self.asInstanceOf[Method[Any]]
    def tag: AtomTag       = AtomTag.Method

    def withIndex(index: Int): Method[A] = copy(index = index)
  }

  private[http] final case class Header[A](name: String, textCodec: TextCodec[A], index: Int = 0)
      extends Atom[HttpCodecType.Header, A] {
    self =>
    def erase: Header[Any] = self.asInstanceOf[Header[Any]]

    def tag: AtomTag = AtomTag.Header

    def withIndex(index: Int): Header[A] = copy(index = index)
  }

  private[http] final case class WithDoc[AtomType, A](in: HttpCodec[AtomType, A], doc: Doc)
      extends HttpCodec[AtomType, A]

  private[http] final case class TransformOrFail[AtomType, X, A](
    api: HttpCodec[AtomType, X],
    f: X => Either[String, A],
    g: A => Either[String, X],
  ) extends HttpCodec[AtomType, A] {
    type In  = X
    type Out = A
  }

  private[http] case object Empty extends HttpCodec[Any, Unit]

  private[http] case object Halt extends HttpCodec[Any, Nothing]

  private[http] final case class Combine[AtomType1, AtomType2, A1, A2, A](
    left: HttpCodec[AtomType1, A1],
    right: HttpCodec[AtomType2, A2],
    inputCombiner: Combiner.WithOut[A1, A2, A],
  ) extends HttpCodec[AtomType1 with AtomType2, A] {
    type Left  = A1
    type Right = A2
    type Out   = A
  }

  private[http] final case class Fallback[AtomType, A, B](
    left: HttpCodec[AtomType, A],
    right: HttpCodec[AtomType, B],
  ) extends HttpCodec[AtomType, Either[A, B]] {
    type Left  = A
    type Right = B
    type Out   = Either[A, B]
  }

  private[http] def flattenFallbacks[AtomTypes, A](api: HttpCodec[AtomTypes, A]): Chunk[HttpCodec[AtomTypes, A]] = {
    def rewrite[T, B](api: HttpCodec[T, B]): Chunk[HttpCodec[T, B]] =
      api match {
        case fallback @ HttpCodec.Fallback(left, right) =>
          rewrite[T, fallback.Left](left).map(_.toLeft[fallback.Right]) ++ rewrite[T, fallback.Right](right)
            .map(_.toRight[fallback.Left])

        case transform @ HttpCodec.TransformOrFail(codec, f, g) =>
          rewrite[T, transform.In](codec).map(HttpCodec.TransformOrFail(_, f, g))

        case combine @ HttpCodec.Combine(left, right, combiner) =>
          for {
            l <- rewrite[T, combine.Left](left)
            r <- rewrite[T, combine.Right](right)
          } yield HttpCodec.Combine(l, r, combiner)

        case HttpCodec.WithDoc(in, doc) => rewrite[T, B](in).map(_ ?? doc)

        case HttpCodec.Empty => Chunk.single(HttpCodec.Empty)

        case HttpCodec.Halt => Chunk.empty

        case atom: Atom[_, _] => Chunk.single(atom)
      }

    rewrite(api)
  }
}
