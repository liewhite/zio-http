package example

import zhttp.service.Client
import zhttp.service.transport.Transport.Auto
import zio.{App, ExitCode, URIO, console}

object SimpleClient extends App {
  val env = Auto.eventLoopGroupLayer() ++ Auto.clientLayer
  val url = "http://sports.api.decathlon.com/groups/water-aerobics"

  val program = for {
    res  <- Client.request(url)
    data <- res.bodyAsString
    _    <- console.putStrLn { data }
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = program.exitCode.provideCustomLayer(env)

}
