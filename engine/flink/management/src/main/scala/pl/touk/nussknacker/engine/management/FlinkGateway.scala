package pl.touk.nussknacker.engine.management

import java.util.concurrent.TimeoutException

import akka.pattern.AskTimeoutException
import com.typesafe.scalalogging.LazyLogging
import org.apache.flink.client.program.{ClusterClient, PackagedProgram, StandaloneClusterClient}
import org.apache.flink.configuration.Configuration
import org.apache.flink.queryablestate.client.QueryableStateClient
import pl.touk.nussknacker.engine.flink.queryablestate.EspQueryableClient

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

class DefaultFlinkGateway(config: Configuration,
                          timeout: FiniteDuration) extends FlinkGateway with LazyLogging {

  private implicit val ec = ExecutionContext.Implicits.global

  private val client = createClient()

  override def invokeJobManager[Response: ClassTag](req: AnyRef): Future[Response] = {
    //TODO: this starts/stops leader retrieval service for each invocation. Can we put it into var? But then
    //what about leader changes?
    client.getJobManagerGateway.ask(req, timeout).mapTo[Response]
  }

  override def run(program: PackagedProgram): Unit = {
    logger.debug(s"Deploying right now. PackagedProgram args :${program.getArguments}")
    val submissionResult = client.run(program, -1)
    logger.debug(s"Deployment finished. JobId ${submissionResult.getJobID}")
  }

  def shutDown(): Unit = {
    client.shutdown()
  }

  private def createClient() : ClusterClient = {
    val client = new StandaloneClusterClient(config)
    client.setDetached(true)
    client
  }

}

//TODO: maybe move it to ui and use actors??
class RestartableFlinkGateway(prepareGateway: () => FlinkGateway) extends FlinkGateway with LazyLogging {

  private implicit val ec = ExecutionContext.Implicits.global

  @volatile var gateway: FlinkGateway = _

  override def invokeJobManager[Response: ClassTag](req: AnyRef): Future[Response] = {
    tryToInvokeJobManager[Response](req)
      .recoverWith {
        case e: AskTimeoutException =>
          logger.error("Failed to connect to Flink, restarting", e)
          restart()
          tryToInvokeJobManager[Response](req)
      }
  }

  private def tryToInvokeJobManager[Response: ClassTag](req: AnyRef): Future[Response] = {
    retrieveGateway().invokeJobManager[Response](req)
  }

  override def run(program: PackagedProgram): Unit = {
    tryToRunProgram(program).recover {
      //TODO: is this right exception to detect??
      case e: TimeoutException =>
        logger.error("Failed to connect to Flink, restarting", e)
        restart()
        tryToRunProgram(program)
    }.get
  }
  
  private def tryToRunProgram(program: PackagedProgram): Try[Unit] =
    Try(retrieveGateway().run(program))

  private def restart(): Unit = synchronized {
    shutDown()
    retrieveGateway()
  }

  def retrieveGateway() = synchronized {
    if (gateway == null) {
      logger.info("Creating new gateway")
      gateway = prepareGateway()
      logger.info("Gateway created")
    }
    gateway
  }

  override def shutDown() = synchronized {
    Option(gateway).foreach(_.shutDown())
    gateway = null
    logger.info("Gateway shut down")
  }
}

trait FlinkGateway {

  def invokeJobManager[Response: ClassTag](req: AnyRef): Future[Response]

  def run(program: PackagedProgram): Unit

  def shutDown(): Unit

}
