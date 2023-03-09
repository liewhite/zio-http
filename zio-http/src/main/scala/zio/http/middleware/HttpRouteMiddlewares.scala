package zio.http.middleware

import zio.http.{Http, HttpAppMiddleware, Middleware, Request, Response}
import zio.{Trace, ZIO}

import zio.stacktracer.TracingImplicits.disableAutoTrace // scalafix:ok;

private[zio] trait HttpRoutesMiddlewares extends Cors {

  /**
   * Creates a middleware which can allow or disallow access to an http based on
   * the predicate
   */
  def allow(
    condition: Request => Boolean,
  ): Middleware[Nothing, Any, Any, Nothing] =
    Middleware.allow(condition)

  /**
   * Creates a middleware which can allow or disallow access to an http based on
   * the predicate effect
   */
  def allowZIO[R, Err](
    condition: Request => ZIO[R, Err, Boolean],
  ): Middleware[Nothing, R, Err, Nothing] =
    Middleware.allowZIO(condition)

  /**
   * Removes the trailing slash from the path.
   */
  def dropTrailingSlash: HttpAppMiddleware.Mono[Any, Nothing] =
    new HttpAppMiddleware[Any, Nothing] {
      type OutEnv[Env] = Env
      type OutErr[Err] = Err
      override def apply[R1 <: Any, Err1 >: Nothing](
        http: Http[R1, Err1, Request, Response],
      )(implicit trace: Trace): Http[R1, Err1, Request, Response] =
        Http.fromHandlerZIO[Request] { request =>
          if (request.url.queryParams.isEmpty)
            http.runHandler(request.dropTrailingSlash)
          else
            http.runHandler(request)
        }
    }
}

object HttpRoutesMiddlewares extends HttpRoutesMiddlewares
