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

import java.net.{InetAddress, InetSocketAddress}

import zio._

import zio.http.Server.Config.ResponseCompressionConfig
import zio.http.netty.NettyConfig
import zio.http.netty.server._

/**
 * Represents a server, which is capable of serving zero or more HTTP
 * applications.
 */
trait Server {

  /**
   * Installs the given HTTP application into the server.
   */
  def install[R](httpApp: App[R])(implicit trace: Trace): URIO[R, Unit]

  /**
   * The port on which the server is listening.
   *
   * @return
   */
  def port: Int
}

object Server {
  final case class Config(
    sslConfig: Option[SSLConfig],
    address: InetSocketAddress,
    acceptContinue: Boolean,
    keepAlive: Boolean,
    consolidateFlush: Boolean,
    flowControl: Boolean,
    requestDecompression: Decompression,
    responseCompression: Option[ResponseCompressionConfig],
    objectAggregator: Int,
    maxHeaderSize: Int,
  ) {
    self =>
    def useAggregator: Boolean = objectAggregator >= 0

    /**
     * Configure the server to use HttpServerExpectContinueHandler to send a 100
     * HttpResponse if necessary.
     */
    def acceptContinue(enable: Boolean): Config = self.copy(acceptContinue = enable)

    /**
     * Configure the server to listen on the provided hostname and port.
     */
    def binding(hostname: String, port: Int): Config =
      self.copy(address = new InetSocketAddress(hostname, port))

    /**
     * Configure the server to listen on the provided InetAddress and port.
     */
    def binding(address: InetAddress, port: Int): Config =
      self.copy(address = new InetSocketAddress(address, port))

    /**
     * Configure the server to listen on the provided InetSocketAddress.
     */
    def binding(inetSocketAddress: InetSocketAddress): Config = self.copy(address = inetSocketAddress)

    /**
     * Configure the server to use FlushConsolidationHandler to control the
     * flush operations in a more efficient way if enabled (@see <a
     * href="https://netty.io/4.1/api/io/netty/handler/flush/FlushConsolidationHandler.html">FlushConsolidationHandler<a>).
     */
    def consolidateFlush(enable: Boolean): Config = self.copy(consolidateFlush = enable)

    /**
     * Configure the server to use netty FlowControlHandler if enable (@see <a
     * href="https://netty.io/4.1/api/io/netty/handler/flow/FlowControlHandler.html">FlowControlHandler</a>).
     */
    def flowControl(enable: Boolean): Config = self.copy(flowControl = enable)

    /**
     * Configure the server to use netty's HttpServerKeepAliveHandler to close
     * persistent connections when enable is true (@see <a
     * href="https://netty.io/4.1/api/io/netty/handler/codec/http/HttpServerKeepAliveHandler.html">HttpServerKeepAliveHandler</a>).
     */
    def keepAlive(enable: Boolean): Config = self.copy(keepAlive = enable)

    /**
     * Configure the server to use HttpObjectAggregator with the specified max
     * size of the aggregated content.
     */
    def objectAggregator(maxRequestSize: Int = 1024 * 100): Config =
      self.copy(objectAggregator = maxRequestSize)

    /**
     * Configure the server to listen on an available open port
     */
    def onAnyOpenPort: Config = port(0)

    /**
     * Configure the server to listen on the provided port.
     */
    def port(port: Int): Config = self.copy(address = new InetSocketAddress(port))

    /**
     * Configure the server to use netty's HttpContentDecompressor to decompress
     * Http requests (@see <a href =
     * "https://netty.io/4.1/api/io/netty/handler/codec/http/HttpContentDecompressor.html">HttpContentDecompressor</a>).
     */
    def requestDecompression(isStrict: Boolean): Config =
      self.copy(requestDecompression = if (isStrict) Decompression.Strict else Decompression.NonStrict)

    /**
     * Configure the new server with netty's HttpContentCompressor to compress
     * Http responses (@see <a href =
     * "https://netty.io/4.1/api/io/netty/handler/codec/http/HttpContentCompressor.html"HttpContentCompressor</a>).
     */
    def responseCompression(rCfg: ResponseCompressionConfig = Config.ResponseCompressionConfig.default): Config =
      self.copy(responseCompression = Option(rCfg))

    /**
     * Configure the server with the following ssl options.
     */
    def ssl(sslConfig: SSLConfig): Config = self.copy(sslConfig = Some(sslConfig))

    /**
     * Configure the server to use `maxHeaderSize` value when encode/decode
     * headers.
     */
    def maxHeaderSize(headerSize: Int): Config = self.copy(maxHeaderSize = headerSize)
  }

  object Config {
    lazy val config: zio.Config[Config] = {
      SSLConfig.config.optional ++
        zio.Config.string("binding-host").optional ++
        zio.Config.int("binding-port").withDefault(Config.default.address.getPort) ++
        zio.Config.boolean("accept-continue").withDefault(Config.default.acceptContinue) ++
        zio.Config.boolean("keep-alive").withDefault(Config.default.keepAlive) ++
        zio.Config.boolean("consolidate-flush").withDefault(Config.default.consolidateFlush) ++
        zio.Config.boolean("flow-control").withDefault(Config.default.flowControl) ++
        Decompression.config.nested("request-decompression").withDefault(Config.default.requestDecompression) ++
        ResponseCompressionConfig.config.nested("response-compression").optional ++
        zio.Config.int("max-aggregated-request-size").withDefault(Config.default.objectAggregator) ++
        zio.Config.int("max-header-size").withDefault(Config.default.maxHeaderSize)
    }.map {
      case (
            sslConfig,
            host,
            port,
            acceptContinue,
            keepAlive,
            consolidateFlush,
            flowControl,
            requestDecompression,
            responseCompression,
            objectAggregator,
            maxHeaderSize,
          ) =>
        Config(
          sslConfig = sslConfig,
          address = new InetSocketAddress(host.getOrElse(Config.default.address.getHostName), port),
          acceptContinue = acceptContinue,
          keepAlive = keepAlive,
          consolidateFlush = consolidateFlush,
          flowControl = flowControl,
          requestDecompression = requestDecompression,
          responseCompression = responseCompression,
          objectAggregator = objectAggregator,
          maxHeaderSize = maxHeaderSize,
        )
    }

    lazy val default: Config = Config(
      sslConfig = None,
      address = new InetSocketAddress(8080),
      acceptContinue = false,
      keepAlive = true,
      consolidateFlush = false,
      flowControl = true,
      requestDecompression = Decompression.No,
      responseCompression = None,
      objectAggregator = 1024 * 100,
      maxHeaderSize = 8192,
    )

    final case class ResponseCompressionConfig(
      contentThreshold: Int,
      options: IndexedSeq[CompressionOptions],
    )

    object ResponseCompressionConfig {
      lazy val config: zio.Config[ResponseCompressionConfig] =
        (
          zio.Config.int("content-threshold").withDefault(ResponseCompressionConfig.default.contentThreshold) ++
            CompressionOptions.config.repeat.nested("options")
        ).map { case (contentThreshold, options) =>
          ResponseCompressionConfig(contentThreshold, options)
        }

      lazy val default: ResponseCompressionConfig =
        ResponseCompressionConfig(0, IndexedSeq(CompressionOptions.gzip(), CompressionOptions.deflate()))
    }

    /**
     * @param level
     *   defines compression level, {@code 1} yields the fastest compression and
     *   {@code 9} yields the best compression. {@code 0} means no compression.
     * @param bits
     *   defines windowBits, The base two logarithm of the size of the history
     *   buffer. The value should be in the range {@code 9} to {@code 15}
     *   inclusive. Larger values result in better compression at the expense of
     *   memory usage
     * @param mem
     *   defines memlevel, How much memory should be allocated for the internal
     *   compression state. {@code 1} uses minimum memory and {@code 9} uses
     *   maximum memory. Larger values result in better and faster compression
     *   at the expense of memory usage
     */
    final case class CompressionOptions(
      level: Int,
      bits: Int,
      mem: Int,
      kind: CompressionOptions.CompressionType,
    )

    object CompressionOptions {
      val DefaultLevel = 6
      val DefaultBits  = 15
      val DefaultMem   = 8

      /**
       * Creates GZip CompressionOptions. Defines defaults as per
       * io.netty.handler.codec.compression.GzipOptions#DEFAULT
       */
      def gzip(level: Int = DefaultLevel, bits: Int = DefaultBits, mem: Int = DefaultMem): CompressionOptions =
        CompressionOptions(level, bits, mem, CompressionType.GZip)

      /**
       * Creates Deflate CompressionOptions. Defines defaults as per
       * io.netty.handler.codec.compression.DeflateOptions#DEFAULT
       */
      def deflate(level: Int = DefaultLevel, bits: Int = DefaultBits, mem: Int = DefaultMem): CompressionOptions =
        CompressionOptions(level, bits, mem, CompressionType.Deflate)

      sealed trait CompressionType

      private[http] object CompressionType {
        case object GZip    extends CompressionType
        case object Deflate extends CompressionType

        lazy val config: zio.Config[CompressionType] =
          zio.Config.string.mapOrFail {
            case "gzip"    => Right(GZip)
            case "deflate" => Right(Deflate)
            case other     => Left(zio.Config.Error.InvalidData(message = s"Invalid compression type: $other"))
          }
      }

      lazy val config: zio.Config[CompressionOptions] =
        (
          zio.Config.int("level").withDefault(DefaultLevel) ++
            zio.Config.int("bits").withDefault(DefaultBits) ++
            zio.Config.int("mem").withDefault(DefaultMem) ++
            CompressionOptions.CompressionType.config.nested("type"),
        ).map { case (level, bits, mem, kind) =>
          CompressionOptions(level, bits, mem, kind)
        }
    }
  }

  def serve[R](
    httpApp: App[R],
  )(implicit trace: Trace): URIO[R with Server, Nothing] =
    install(httpApp) *> ZIO.never

  def install[R](httpApp: App[R])(implicit trace: Trace): URIO[R with Server, Int] = {
    ZIO.serviceWithZIO[Server](_.install(httpApp)) *> ZIO.service[Server].map(_.port)
  }

  private val base: ZLayer[Driver, Throwable, Server] = {
    implicit val trace: Trace = Trace.empty
    ZLayer.scoped {
      for {
        driver <- ZIO.service[Driver]
        port   <- driver.start
      } yield ServerLive(driver, port)
    }
  }

  def configured(path: String = "zio.http.server"): ZLayer[Any, Throwable, Server] =
    ZLayer(ZIO.config(Config.config.nested(path))).mapError(error =>
      new RuntimeException(s"Configuration error: $error"),
    ) >>> live

  val customized: ZLayer[Config & NettyConfig, Throwable, Server] = {
    implicit val trace: Trace = Trace.empty
    NettyDriver.customized >>> base
  }

  def defaultWithPort(port: Int)(implicit trace: Trace): ZLayer[Any, Throwable, Server] =
    defaultWith(_.port(port))

  def defaultWith(f: Config => Config)(implicit trace: Trace): ZLayer[Any, Throwable, Server] =
    ZLayer.succeed(f(Config.default)) >>> live

  val default: ZLayer[Any, Throwable, Server] = {
    implicit val trace: Trace = Trace.empty
    ZLayer.succeed(Config.default) >>> live
  }

  lazy val live: ZLayer[Config, Throwable, Server] = {
    implicit val trace: Trace = Trace.empty
    NettyDriver.live >>> base
  }

  private final case class ServerLive(
    driver: Driver,
    bindPort: Int,
  ) extends Server {
    override def install[R](httpApp: App[R])(implicit
      trace: Trace,
    ): URIO[R, Unit] =
      ZIO.environment[R].flatMap(driver.addApp(httpApp, _))

    override def port: Int = bindPort
  }
}
