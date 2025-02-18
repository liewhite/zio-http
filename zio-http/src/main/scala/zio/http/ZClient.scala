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

package zio.http

import zio._
import zio.http.DnsResolver.Config
import zio.http.URL.Location
import zio.http.model._
import zio.http.model.headers.HeaderOps
import zio.http.netty.{ChannelType, EventLoopGroups, NettyConfig}
import zio.http.netty.client._
import zio.http.socket.SocketApp

import java.net.{InetSocketAddress, URI} // scalafix:ok;

trait ZClient[-Env, -In, +Err, +Out] extends HeaderOps[ZClient[Env, In, Err, Out]] { self =>

  def headers: Headers

  def hostOption: Option[String]

  def pathPrefix: Path

  def portOption: Option[Int]

  def queries: QueryParams

  def schemeOption: Option[Scheme]

  def sslConfig: Option[ClientSSLConfig]

  override def updateHeaders(update: Headers => Headers): ZClient[Env, In, Err, Out] =
    new ZClient[Env, In, Err, Out] {
      override def headers: Headers = update(self.headers)

      override def hostOption: Option[String] = self.hostOption

      override def pathPrefix: Path = self.pathPrefix

      override def portOption: Option[RuntimeFlags] = self.portOption

      override def queries: QueryParams = self.queries

      override def schemeOption: Option[Scheme] = self.schemeOption

      override def sslConfig: Option[ClientSSLConfig] = self.sslConfig

      override def request(
        body: In,
        headers: Headers,
        hostOption: Option[String],
        method: Method,
        pathPrefix: Path,
        portOption: Option[RuntimeFlags],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        sslConfig: Option[ClientSSLConfig],
        version: Version,
      )(implicit trace: Trace): ZIO[Env, Err, Out] =
        self.request(
          body,
          headers,
          hostOption,
          method,
          pathPrefix,
          portOption,
          queries,
          schemeOption,
          sslConfig,
          version,
        )

      override def socket[Env1 <: Env](
        app: SocketApp[Env1],
        headers: Headers,
        hostOption: Option[String],
        pathPrefix: Path,
        portOption: Option[RuntimeFlags],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        version: Version,
      )(implicit trace: Trace): ZIO[Env1 with Scope, Err, Out] =
        self.socket(app, headers, hostOption, pathPrefix, portOption, queries, schemeOption, version)
    }

  /**
   * Applies the specified client aspect, which can modify the execution of this
   * client.
   */
  final def @@[
    LowerEnv <: UpperEnv,
    UpperEnv <: Env,
    LowerIn <: UpperIn,
    UpperIn <: In,
    LowerErr >: Err,
    UpperErr >: LowerErr,
    LowerOut >: Out,
    UpperOut >: LowerOut,
  ](
    aspect: ZClientAspect[LowerEnv, UpperEnv, LowerIn, UpperIn, LowerErr, UpperErr, LowerOut, UpperOut],
  ): ZClient[UpperEnv, UpperIn, LowerErr, LowerOut] =
    aspect(self)

  final def contramap[In2](f: In2 => In): ZClient[Env, In2, Err, Out] =
    contramapZIO(in => ZIO.succeed(f(in)))

  final def contramapZIO[Env1 <: Env, Err1 >: Err, In2](f: In2 => ZIO[Env1, Err1, In]): ZClient[Env1, In2, Err1, Out] =
    new ZClient[Env1, In2, Err1, Out] {
      def headers: Headers                   = self.headers
      def hostOption: Option[String]         = self.hostOption
      def pathPrefix: Path                   = self.pathPrefix
      def portOption: Option[Int]            = self.portOption
      def queries: QueryParams               = self.queries
      def schemeOption: Option[Scheme]       = self.schemeOption
      def sslConfig: Option[ClientSSLConfig] = self.sslConfig
      def request(
        body: In2,
        headers: Headers,
        hostOption: Option[String],
        method: Method,
        pathPrefix: Path,
        portOption: Option[Int],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        sslConfig: Option[ClientSSLConfig],
        version: Version,
      )(implicit trace: Trace): ZIO[Env1, Err1, Out] =
        f(body).flatMap { body =>
          self.request(
            body,
            headers,
            hostOption,
            method,
            pathPrefix,
            portOption,
            queries,
            schemeOption,
            sslConfig,
            version,
          )
        }
      def socket[Env2 <: Env1](
        app: SocketApp[Env2],
        headers: Headers,
        hostOption: Option[String],
        pathPrefix: Path,
        portOption: Option[Int],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        version: Version,
      )(implicit trace: Trace): ZIO[Env2 with Scope, Err1, Out] =
        self.socket(
          app,
          headers,
          hostOption,
          pathPrefix,
          portOption,
          queries,
          schemeOption,
          version,
        )
    }

  final def delete(pathSuffix: String, body: In)(implicit trace: Trace): ZIO[Env, Err, Out] =
    request(Method.DELETE, pathSuffix, body)

  final def delete(pathSuffix: String)(implicit trace: Trace, ev: Body <:< In): ZIO[Env, Err, Out] =
    delete(pathSuffix, ev(Body.empty))

  final def dieOn(
    f: Err => Boolean,
  )(implicit ev1: Err IsSubtypeOfError Throwable, ev2: CanFail[Err], trace: Trace): ZClient[Env, In, Err, Out] =
    refineOrDie { case e if !f(e) => e }

  final def get(pathSuffix: String, body: In)(implicit trace: Trace): ZIO[Env, Err, Out] =
    request(Method.GET, pathSuffix, body)

  final def get(pathSuffix: String)(implicit trace: Trace, ev: Body <:< In): ZIO[Env, Err, Out] =
    get(pathSuffix, ev(Body.empty))

  final def head(pathSuffix: String, body: In)(implicit trace: Trace): ZIO[Env, Err, Out] =
    request(Method.HEAD, pathSuffix, body)

  final def head(pathSuffix: String)(implicit trace: Trace, ev: Body <:< In): ZIO[Env, Err, Out] =
    head(pathSuffix, Body.empty)

  final def host(host: String): ZClient[Env, In, Err, Out] =
    copy(hostOption = Some(host))

  final def map[Out2](f: Out => Out2): ZClient[Env, In, Err, Out2] =
    mapZIO(out => ZIO.succeed(f(out)))

  final def mapZIO[Env1 <: Env, Err1 >: Err, Out2](f: Out => ZIO[Env1, Err1, Out2]): ZClient[Env1, In, Err1, Out2] =
    new ZClient[Env1, In, Err1, Out2] {
      def headers: Headers                   = self.headers
      def hostOption: Option[String]         = self.hostOption
      def pathPrefix: Path                   = self.pathPrefix
      def portOption: Option[Int]            = self.portOption
      def queries: QueryParams               = self.queries
      def schemeOption: Option[Scheme]       = self.schemeOption
      def sslConfig: Option[ClientSSLConfig] = self.sslConfig
      def request(
        body: In,
        headers: Headers,
        hostOption: Option[String],
        method: Method,
        pathPrefix: Path,
        portOption: Option[Int],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        sslConfig: Option[ClientSSLConfig],
        version: Version,
      )(implicit trace: Trace): ZIO[Env1, Err1, Out2] =
        self
          .request(
            body,
            headers,
            hostOption,
            method,
            pathPrefix,
            portOption,
            queries,
            schemeOption,
            sslConfig,
            version,
          )
          .flatMap(f)
      def socket[Env2 <: Env1](
        app: SocketApp[Env2],
        headers: Headers,
        hostOption: Option[String],
        pathPrefix: Path,
        portOption: Option[Int],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        version: Version,
      )(implicit trace: Trace): ZIO[Env2 with Scope, Err1, Out2] =
        self
          .socket(
            app,
            headers,
            hostOption,
            pathPrefix,
            portOption,
            queries,
            schemeOption,
            version,
          )
          .flatMap(f)
    }

  final def path(segment: String): ZClient[Env, In, Err, Out] =
    copy(pathPrefix = pathPrefix / segment)

  final def port(port: Int): ZClient[Env, In, Err, Out] =
    copy(portOption = Some(port))

  final def patch(pathSuffix: String, body: In)(implicit trace: Trace): ZIO[Env, Err, Out] =
    request(Method.PATCH, pathSuffix, body)

  final def post(pathSuffix: String, body: In)(implicit trace: Trace): ZIO[Env, Err, Out] =
    request(Method.POST, pathSuffix, body)

  final def put(pathSuffix: String, body: In)(implicit trace: Trace): ZIO[Env, Err, Out] =
    request(Method.PUT, pathSuffix, body)

  def query(key: String, value: String): ZClient[Env, In, Err, Out] =
    copy(queries = queries.add(key, value))

  final def refineOrDie[Err2](
    pf: PartialFunction[Err, Err2],
  )(implicit ev1: Err IsSubtypeOfError Throwable, ev2: CanFail[Err], trace: Trace): ZClient[Env, In, Err2, Out] =
    new ZClient[Env, In, Err2, Out] {
      def headers: Headers                   = self.headers
      def hostOption: Option[String]         = self.hostOption
      def pathPrefix: Path                   = self.pathPrefix
      def portOption: Option[Int]            = self.portOption
      def queries: QueryParams               = self.queries
      def schemeOption: Option[Scheme]       = self.schemeOption
      def sslConfig: Option[ClientSSLConfig] = self.sslConfig
      def request(
        body: In,
        headers: Headers,
        hostOption: Option[String],
        method: Method,
        pathPrefix: Path,
        portOption: Option[Int],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        sslConfig: Option[ClientSSLConfig],
        version: Version,
      )(implicit trace: Trace): ZIO[Env, Err2, Out] =
        self
          .request(
            body,
            headers,
            hostOption,
            method,
            pathPrefix,
            portOption,
            queries,
            schemeOption,
            sslConfig,
            version,
          )
          .refineOrDie(pf)
      def socket[Env1 <: Env](
        app: SocketApp[Env1],
        headers: Headers,
        hostOption: Option[String],
        pathPrefix: Path,
        portOption: Option[Int],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        version: Version,
      )(implicit trace: Trace): ZIO[Env1 with Scope, Err2, Out] =
        self
          .socket(
            app,
            headers,
            hostOption,
            pathPrefix,
            portOption,
            queries,
            schemeOption,
            version,
          )
          .refineOrDie(pf)
    }

  final def request(method: Method, pathSuffix: String, body: In)(implicit trace: Trace): ZIO[Env, Err, Out] =
    request(
      body,
      headers,
      hostOption,
      method,
      pathPrefix / pathSuffix,
      portOption,
      queries,
      schemeOption,
      sslConfig,
      Version.Http_1_1,
    )

  final def request(request: Request)(implicit ev: Body <:< In, trace: Trace): ZIO[Env, Err, Out] = {
    self.request(
      ev(request.body),
      headers ++ request.headers,
      request.url.host,
      request.method,
      pathPrefix ++ request.path,
      request.url.port,
      queries ++ request.url.queryParams,
      request.url.scheme,
      sslConfig,
      request.version,
    )
  }

  final def retry[Env1 <: Env](policy: Schedule[Env1, Err, Any]): ZClient[Env1, In, Err, Out] =
    new ZClient[Env1, In, Err, Out] {
      def headers: Headers                   = self.headers
      def hostOption: Option[String]         = self.hostOption
      def pathPrefix: Path                   = self.pathPrefix
      def portOption: Option[Int]            = self.portOption
      def queries: QueryParams               = self.queries
      def schemeOption: Option[Scheme]       = self.schemeOption
      def sslConfig: Option[ClientSSLConfig] = self.sslConfig
      def request(
        body: In,
        headers: Headers,
        hostOption: Option[String],
        method: Method,
        pathPrefix: Path,
        portOption: Option[Int],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        sslConfig: Option[ClientSSLConfig],
        version: Version,
      )(implicit trace: Trace): ZIO[Env1, Err, Out] =
        self
          .request(
            body,
            headers,
            hostOption,
            method,
            pathPrefix,
            portOption,
            queries,
            schemeOption,
            sslConfig,
            version,
          )
          .retry(policy)
      def socket[Env2 <: Env1](
        app: SocketApp[Env2],
        headers: Headers,
        hostOption: Option[String],
        pathPrefix: Path,
        portOption: Option[Int],
        queries: QueryParams,
        schemeOption: Option[Scheme],
        version: Version,
      )(implicit trace: Trace): ZIO[Env2 with Scope, Err, Out] =
        self
          .socket(
            app,
            headers,
            hostOption,
            pathPrefix,
            portOption,
            queries,
            schemeOption,
            version,
          )
          .retry(policy)
    }

  final def scheme(scheme: Scheme): ZClient[Env, In, Err, Out] =
    copy(schemeOption = Some(scheme))

  final def socket[Env1 <: Env](
    pathSuffix: String,
  )(app: SocketApp[Env1])(implicit trace: Trace): ZIO[Env1 with Scope, Err, Out] =
    socket(
      app,
      headers,
      hostOption,
      pathPrefix / pathSuffix,
      portOption,
      queries,
      schemeOption,
      Version.Http_1_1,
    )

  final def socket[Env1 <: Env](
    url: String,
    app: SocketApp[Env1],
    headers: Headers = Headers.empty,
  )(implicit trace: Trace): ZIO[Env1 with Scope, Err, Out] =
    for {
      url <- ZIO.fromEither(URL.fromString(url)).orDie
      out <- socket(
        app,
        headers,
        url.host,
        pathPrefix ++ url.path,
        url.port,
        queries ++ url.queryParams,
        url.scheme,
        Version.Http_1_1,
      )
    } yield out

  final def ssl(ssl: ClientSSLConfig): ZClient[Env, In, Err, Out] =
    copy(sslConfig = Some(ssl))

  final def uri(uri: URI): ZClient[Env, In, Err, Out] = {
    val scheme = Scheme.decode(uri.getScheme)

    copy(
      hostOption = Option(uri.getHost),
      pathPrefix = pathPrefix ++ Path.decode(uri.getRawPath),
      portOption = Option(uri.getPort).filter(_ != -1).orElse(scheme.map(_.defaultPort)),
      queries = queries ++ QueryParams.decode(uri.getRawQuery),
      schemeOption = scheme,
    )
  }

  final def url(url: URL): ZClient[Env, In, Err, Out] = {
    copy(
      hostOption = url.host,
      pathPrefix = pathPrefix ++ url.path,
      portOption = url.port,
      queries = queries ++ url.queryParams,
      schemeOption = url.scheme,
    )
  }

  def request(
    body: In,
    headers: Headers,
    hostOption: Option[String],
    method: Method,
    pathPrefix: Path,
    portOption: Option[Int],
    queries: QueryParams,
    schemeOption: Option[Scheme],
    sslConfig: Option[ClientSSLConfig],
    version: Version,
  )(implicit trace: Trace): ZIO[Env, Err, Out]

  def socket[Env1 <: Env](
    app: SocketApp[Env1],
    headers: Headers,
    hostOption: Option[String],
    pathPrefix: Path,
    portOption: Option[Int],
    queries: QueryParams,
    schemeOption: Option[Scheme],
    version: Version,
  )(implicit trace: Trace): ZIO[Env1 with Scope, Err, Out]

  private final def copy(
    headers: Headers = headers,
    hostOption: Option[String] = hostOption,
    pathPrefix: Path = pathPrefix,
    portOption: Option[Int] = portOption,
    queries: QueryParams = queries,
    schemeOption: Option[Scheme] = schemeOption,
    sslConfig: Option[ClientSSLConfig] = sslConfig,
  ): ZClient[Env, In, Err, Out] =
    ZClient.Proxy[Env, In, Err, Out](
      self,
      headers,
      hostOption,
      pathPrefix,
      portOption,
      queries,
      schemeOption,
      sslConfig,
    )
}

object ZClient {

  case class Config(
    socketApp: Option[SocketApp[Any]],
    ssl: Option[ClientSSLConfig],
    proxy: Option[zio.http.Proxy],
    useAggregator: Boolean,
    connectionPool: ConnectionPoolConfig,
    maxHeaderSize: Int,
    requestDecompression: Decompression,
    localAddress: Option[InetSocketAddress],
  ) {
    self =>
    def ssl(ssl: ClientSSLConfig): Config = self.copy(ssl = Some(ssl))

    def socketApp(socketApp: SocketApp[Any]): Config = self.copy(socketApp = Some(socketApp))

    def proxy(proxy: zio.http.Proxy): Config = self.copy(proxy = Some(proxy))

    def useObjectAggregator(objectAggregator: Boolean): Config = self.copy(useAggregator = objectAggregator)

    def withFixedConnectionPool(size: Int): Config =
      self.copy(connectionPool = ConnectionPoolConfig.Fixed(size))

    def withDynamicConnectionPool(minimum: Int, maximum: Int, ttl: Duration): Config =
      self.copy(connectionPool = ConnectionPoolConfig.Dynamic(minimum = minimum, maximum = maximum, ttl = ttl))

    /**
     * Configure the client to use `maxHeaderSize` value when encode/decode
     * headers.
     */
    def maxHeaderSize(headerSize: Int): Config = self.copy(maxHeaderSize = headerSize)

    def requestDecompression(isStrict: Boolean): Config =
      self.copy(requestDecompression = if (isStrict) Decompression.Strict else Decompression.NonStrict)
  }

  object Config {
    lazy val config: zio.Config[Config] =
      (
        ClientSSLConfig.config.nested("ssl").optional.withDefault(Config.default.ssl) ++
          zio.http.Proxy.config.nested("proxy").optional.withDefault(Config.default.proxy) ++
          zio.Config.boolean("use-aggregator").withDefault(Config.default.useAggregator) ++
          ConnectionPoolConfig.config.nested("connection-pool").withDefault(Config.default.connectionPool) ++
          zio.Config.int("max-header-size").withDefault(Config.default.maxHeaderSize) ++
          Decompression.config.nested("request-decompression").withDefault(Config.default.requestDecompression)
      ).map { case (ssl, proxy, useAggregator, connectionPool, maxHeaderSize, requestDecompression) =>
        default.copy(
          ssl = ssl,
          proxy = proxy,
          useAggregator = useAggregator,
          connectionPool = connectionPool,
          maxHeaderSize = maxHeaderSize,
          requestDecompression = requestDecompression,
        )
      }

    lazy val default: Config = Config(
      socketApp = None,
      ssl = None,
      proxy = None,
      useAggregator = true,
      connectionPool = ConnectionPoolConfig.Disabled,
      maxHeaderSize = 8192,
      requestDecompression = Decompression.No,
      localAddress = None,
    )
  }

  private final case class Proxy[-Env, -In, +Err, +Out](
    client: ZClient[Env, In, Err, Out],
    headers: Headers,
    hostOption: Option[String],
    pathPrefix: Path,
    portOption: Option[Int],
    queries: QueryParams,
    schemeOption: Option[Scheme],
    sslConfig: Option[ClientSSLConfig],
  ) extends ZClient[Env, In, Err, Out] {

    def request(
      body: In,
      headers: Headers,
      hostOption: Option[String],
      method: Method,
      path: Path,
      portOption: Option[Int],
      queries: QueryParams,
      schemeOption: Option[Scheme],
      sslConfig: Option[ClientSSLConfig],
      version: Version,
    )(implicit trace: Trace): ZIO[Env, Err, Out] =
      client.request(
        body,
        headers,
        hostOption,
        method,
        path,
        portOption,
        queries,
        schemeOption,
        sslConfig,
        version,
      )

    def socket[Env1 <: Env](
      app: SocketApp[Env1],
      headers: Headers,
      hostOption: Option[String],
      pathPrefix: Path,
      portOption: Option[Int],
      queries: QueryParams,
      schemeOption: Option[Scheme],
      version: Version,
    )(implicit trace: Trace): ZIO[Env1 with Scope, Err, Out] =
      client.socket(app, headers, hostOption, pathPrefix, portOption, queries, schemeOption, version)

  }

  final class ClientLive private (config: Config, driver: ClientDriver, connectionPool: ConnectionPool[Any])
      extends Client
      with ClientRequestEncoder { self =>

    def this(driver: ClientDriver)(connectionPool: ConnectionPool[driver.Connection])(settings: Config) =
      this(settings, driver, connectionPool.asInstanceOf[ConnectionPool[Any]])

    val headers: Headers                   = Headers.empty
    val hostOption: Option[String]         = config.localAddress.map(_.getHostString)
    val pathPrefix: Path                   = Path.empty
    val portOption: Option[Int]            = config.localAddress.map(_.getPort)
    val queries: QueryParams               = QueryParams.empty
    val schemeOption: Option[Scheme]       = None
    val sslConfig: Option[ClientSSLConfig] = config.ssl

    def request(
      body: Body,
      headers: Headers,
      hostOption: Option[String],
      method: Method,
      path: Path,
      portOption: Option[Int],
      queries: QueryParams,
      schemeOption: Option[Scheme],
      sslConfig: Option[ClientSSLConfig],
      version: Version,
    )(implicit trace: Trace): ZIO[Any, Throwable, Response] = {

      for {
        host <- ZIO.fromOption(hostOption).orElseFail(new IllegalArgumentException("Host is required"))
        port <- ZIO.fromOption(portOption).orElseSucceed(sslConfig.fold(80)(_ => 443))
        baseRequest = Request
          .default(
            method,
            URL(path, URL.Location.Absolute(schemeOption.getOrElse(Scheme.HTTP), host, port)).setQueryParams(queries),
            body,
          )
        request     = baseRequest.copy(
          version = version,
          headers = self.headers ++ baseRequest.headers ++ headers,
        )
        response <- requestAsync(
          request,
          sslConfig.fold(config)(config.ssl),
        )
      } yield response
    }

    override def socket[R](
      app: SocketApp[R],
      headers: Headers,
      hostOption: Option[String],
      path: Path,
      portOption: Option[Int],
      queries: QueryParams,
      schemeOption: Option[Scheme],
      version: Version,
    )(implicit trace: Trace): ZIO[R with Scope, Throwable, Response] =
      for {
        env      <- ZIO.environment[R]
        location <- ZIO.fromOption {
          for {
            host   <- hostOption
            port   <- portOption
            scheme <- schemeOption
          } yield URL.Location.Absolute(scheme, host, port)
        }.orElseSucceed(URL.Location.Relative)
        res      <- requestAsync(
          Request
            .get(URL(path, location))
            .copy(
              version = version,
              headers = self.headers ++ headers,
            ),
          clientConfig = config.copy(socketApp = Some(app.provideEnvironment(env))),
        ).withFinalizer {
          case resp: Response.CloseableResponse => resp.close.orDie
          case _                                => ZIO.unit
        }
      } yield res

    private def requestAsync(request: Request, clientConfig: Config)(implicit
      trace: Trace,
    ): ZIO[Any, Throwable, Response] =
      request.url.kind match {
        case location: Location.Absolute =>
          ZIO.uninterruptibleMask { restore =>
            for {
              onComplete   <- Promise.make[Throwable, ChannelState]
              onResponse   <- Promise.make[Throwable, Response]
              channelFiber <- ZIO.scoped {
                for {
                  connection       <- connectionPool
                    .get(
                      location,
                      clientConfig.proxy,
                      clientConfig.ssl.getOrElse(ClientSSLConfig.Default),
                      clientConfig.maxHeaderSize,
                      clientConfig.requestDecompression,
                      clientConfig.localAddress,
                    )
                    .tapErrorCause(cause => onResponse.failCause(cause))
                    .map(_.asInstanceOf[driver.Connection])
                  channelInterface <-
                    driver
                      .requestOnChannel(
                        connection,
                        location,
                        request,
                        onResponse,
                        onComplete,
                        clientConfig.useAggregator,
                        connectionPool.enableKeepAlive,
                        () => clientConfig.socketApp.getOrElse(SocketApp()),
                      )
                      .tapErrorCause(cause => onResponse.failCause(cause))
                  _                <-
                    onComplete.await.interruptible.exit.flatMap { exit =>
                      if (exit.isInterrupted) {
                        channelInterface.interrupt
                          .zipRight(connectionPool.invalidate(connection))
                          .uninterruptible
                      } else {
                        channelInterface.resetChannel
                          .zip(exit)
                          .map { case (s1, s2) => s1 && s2 }
                          .catchAllCause(_ =>
                            ZIO.succeed(ChannelState.Invalid),
                          ) // In case resetting the channel fails we cannot reuse it
                          .flatMap { channelState =>
                            connectionPool
                              .invalidate(connection)
                              .when(channelState == ChannelState.Invalid)
                          }
                          .uninterruptible
                      }
                    }
                } yield ()
              }.forkDaemon // Needs to live as long as the channel is alive, as the response body may be streaming
              response     <- restore(onResponse.await.onInterrupt {
                onComplete.interrupt *> channelFiber.join.orDie
              })
            } yield response
          }
        case Location.Relative           =>
          ZIO.fail(throw new IllegalArgumentException("Absolute URL is required"))
      }
  }

  def request(
    url: String,
    method: Method = Method.GET,
    headers: Headers = Headers.empty,
    content: Body = Body.empty,
    addZioUserAgentHeader: Boolean = false,
  )(implicit trace: Trace): ZIO[Client, Throwable, Response] = {
    for {
      uri      <- ZIO.fromEither(URL.fromString(url))
      response <- ZIO.serviceWithZIO[Client](
        _.request(
          Request
            .default(method, uri, content)
            .copy(
              headers = headers.combineIf(addZioUserAgentHeader)(Client.defaultUAHeader),
            ),
        ),
      )
    } yield response

  }

  def delete(pathSuffix: String, body: Body)(implicit trace: Trace): ZIO[Client, Throwable, Response] =
    ZIO.serviceWithZIO[Client](_.delete(pathSuffix, body))

  def delete(pathSuffix: String)(implicit trace: Trace): ZIO[Client, Throwable, Response] =
    ZIO.serviceWithZIO[Client](_.delete(pathSuffix))

  def get(pathSuffix: String, body: Body)(implicit trace: Trace): ZIO[Client, Throwable, Response] =
    ZIO.serviceWithZIO[Client](_.get(pathSuffix, body))

  def get(pathSuffix: String)(implicit trace: Trace): ZIO[Client, Throwable, Response] =
    ZIO.serviceWithZIO[Client](_.get(pathSuffix))

  def head(pathSuffix: String, body: Body)(implicit trace: Trace): ZIO[Client, Throwable, Response] =
    ZIO.serviceWithZIO[Client](_.head(pathSuffix, body))

  def head(pathSuffix: String)(implicit trace: Trace): ZIO[Client, Throwable, Response] =
    ZIO.serviceWithZIO[Client](_.head(pathSuffix))

  def patch(pathSuffix: String, body: Body)(implicit trace: Trace): ZIO[Client, Throwable, Response] =
    ZIO.serviceWithZIO[Client](_.patch(pathSuffix, body))

  def post(pathSuffix: String, body: Body)(implicit trace: Trace): ZIO[Client, Throwable, Response] =
    ZIO.serviceWithZIO[Client](_.post(pathSuffix, body))

  def put(pathSuffix: String, body: Body)(implicit trace: Trace): ZIO[Client, Throwable, Response] =
    ZIO.serviceWithZIO[Client](_.put(pathSuffix, body))

  def request(
    request: Request,
  )(implicit trace: Trace): ZIO[Client, Throwable, Response] = ZIO.serviceWithZIO[Client](_.request(request))

  def socket[R](
    url: String,
    app: SocketApp[R],
    headers: Headers = Headers.empty,
  )(implicit trace: Trace): ZIO[R with Client with Scope, Throwable, Response] =
    Unsafe.unsafe { implicit u =>
      ZIO.serviceWithZIO[Client](_.socket(url, app, headers))
    }

  def configured(path: String = "zio.http.client"): ZLayer[DnsResolver, Throwable, Client] =
    (
      ZLayer.service[DnsResolver] ++
        ZLayer(ZIO.config(Config.config.nested(path))) ++
        ZLayer(ZIO.config(NettyConfig.config.nested(path)))
    ).mapError(error => new RuntimeException(s"Configuration error: $error")) >>> live

  val customized: ZLayer[Config with ClientDriver with DnsResolver, Throwable, Client] = {
    implicit val trace: Trace = Trace.empty
    ZLayer.scoped {
      for {
        config         <- ZIO.service[Config]
        driver         <- ZIO.service[ClientDriver]
        dnsResolver    <- ZIO.service[DnsResolver]
        connectionPool <- driver.createConnectionPool(dnsResolver, config.connectionPool)
      } yield new ClientLive(driver)(connectionPool)(config)
    }
  }

  val default: ZLayer[Any, Throwable, Client] = {
    implicit val trace: Trace = Trace.empty
    (ZLayer.succeed(Config.default) ++ ZLayer.succeed(NettyConfig.default) ++
      DnsResolver.default) >>> live
  }

  lazy val live: ZLayer[ZClient.Config with NettyConfig with DnsResolver, Throwable, Client] = {
    implicit val trace: Trace = Trace.empty
    (NettyClientDriver.live ++ ZLayer.service[DnsResolver]) >>> customized
  }.fresh

  private val zioHttpVersion: String                   = Client.getClass().getPackage().getImplementationVersion()
  private val zioHttpVersionNormalized: Option[String] = Option(zioHttpVersion)

  private val scalaVersion: String = util.Properties.versionString
  val defaultUAHeader: Headers     = Headers(
    Header.UserAgent
      .Complete(
        Header.UserAgent.Product("Zio-Http-Client", zioHttpVersionNormalized),
        Some(Header.UserAgent.Comment(s"Scala $scalaVersion")),
      )
      .untyped,
  )
}
