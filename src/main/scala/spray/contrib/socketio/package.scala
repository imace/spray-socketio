package spray.contrib

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.pattern._
import akka.pattern.ask
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio.ConnectionActive.Awake
import spray.contrib.socketio.Namespace.AskConnectionContext
import spray.contrib.socketio.Namespace.Connecting
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.transport
import spray.http.HttpHeaders
import spray.http.HttpHeaders._
import spray.http.HttpMethods
import spray.http.HttpMethods._
import spray.http.HttpOrigin
import spray.http.HttpProtocols
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.SomeOrigins
import spray.http.StatusCodes
import spray.http.Uri

package object socketio {
  val SOCKET_IO = "socket.io"

  val Settings = new Settings(ConfigFactory.load().getConfig("spray.socketio"))
  class Settings(config: Config) {
    val SupportedTransports = config.getString("server.supported-transports")
    val HeartbeatTimeout = config.getInt("server.heartbeat-timeout")
    val CloseTimeout = config.getInt("server.close-timeout")
    val NamespacesDispatcher = config.getString("namespaces-dispatcher")
    val NamespaceDispatcher = config.getString("namespace-dispatcher")
  }

  private[socketio] final class SoConnectingContext(
    var sessionId: String,
    val serverConnection: ActorRef,
    val namespaces: ActorRef,
    val log: LoggingAdapter,
    val system: ActorSystem,
    implicit val ec: ExecutionContext)

  final case class HandshakeState(response: HttpResponse, sessionId: String, qurey: Uri.Query, origins: Seq[HttpOrigin])
  /**
   * For generic socket.io server
   */
  object HandshakeRequest {
    def unapply(req: HttpRequest): Option[HandshakeState] = req match {
      case HttpRequest(_, uri, headers, _, _) =>
        uri.path.toString.split("/") match {
          case Array("", SOCKET_IO, protocalVersion) =>
            val origins = headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
            val originsHeaders = List(
              HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(origins)),
              HttpHeaders.`Access-Control-Allow-Credentials`(true))

            val respHeaders = List(HttpHeaders.Connection("keep-alive")) ::: originsHeaders
            val sessionId = UUID.randomUUID.toString
            val respEntity = List(sessionId, Settings.HeartbeatTimeout, Settings.CloseTimeout, Settings.SupportedTransports).mkString(":")

            val resp = HttpResponse(
              status = StatusCodes.OK,
              entity = respEntity,
              headers = respHeaders)

            Some(HandshakeState(resp, sessionId, uri.query, origins))

          case _ => None
        }

      case _ => None
    }
  }

  final case class HandshakeContext(response: HttpResponse, sessionId: String, heartbeatTimeout: Int, closeTimeout: Int)
  /**
   * Response that socket.io client got during socket.io handshake
   */
  object HandshakeResponse {
    def unapply(resp: HttpResponse): Option[HandshakeContext] = resp match {
      case HttpResponse(StatusCodes.OK, entity, headers, _) =>
        entity.asString.split(":") match {
          case Array(sessionId, heartbeatTimeout, closeTimeout, supportedTransports, _*) if supportedTransports.split(",").map(_.trim).contains(transport.WebSocket.ID) =>
            Some(HandshakeContext(resp, sessionId, heartbeatTimeout.toInt, closeTimeout.toInt))
          case _ => None
        }

      case _ => None
    }
  }

  def wsConnected(req: HttpRequest)(implicit ctx: SoConnectingContext): Option[Boolean] = {
    val query = req.uri.query
    val origins = req.headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
    req.uri.path.toString.split("/") match {
      case Array("", SOCKET_IO, protocalVersion, transport.WebSocket.ID, sessionId) =>
        ctx.sessionId = sessionId
        import ctx.ec
        val connecting = Namespace.Connecting(sessionId, query, origins, new transport.WebSocket(ctx.system, ctx.serverConnection))
        for {
          connContextOpt <- ctx.namespaces.ask(connecting)(5.seconds).mapTo[Option[ConnectionContext]]
          connContext <- connContextOpt
        } {
          connContext.transport.asInstanceOf[transport.WebSocket].sendPacket(ConnectPacket())
          connContext.connectionActive ! Awake
        }
        Some(true)
      case _ =>
        None
    }
  }

  /**
   * Test websocket frame under socketio
   */
  object WsFrame {
    def unapply(frame: TextFrame)(implicit ctx: SoConnectingContext): Option[Boolean] = frame match {
      case TextFrame(payload) =>
        ctx.log.debug("WebSocket with sessionId: {} ", ctx.sessionId) // ctx.sessionId should not be null
        import ctx.ec
        for {
          connContextOpt <- ctx.namespaces.ask(Namespace.AskConnectionContext(ctx.sessionId))(5.seconds).mapTo[Option[ConnectionContext]]
          connContext <- connContextOpt
        } {
          connContext.transport.asInstanceOf[transport.WebSocket].onPayload(ctx.serverConnection, payload)
        }
        Some(true)
      case _ => None
    }
  }

  /**
   * Test http get request under socketio
   */
  object HttpGet {
    def unapply(req: HttpRequest)(implicit ctx: SoConnectingContext): Option[Boolean] = req match {
      case HttpRequest(HttpMethods.GET, uri, _, _, HttpProtocols.`HTTP/1.1`) =>
        val query = req.uri.query
        val origins = req.headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
        uri.path.toString.split("/") match {
          case Array("", SOCKET_IO, protocalVersion, transport.XhrPolling.ID, sessionId) =>
            import ctx.ec
            for {
              connContextOpt <- ctx.namespaces.ask(Namespace.AskConnectionContext(sessionId))(5.seconds).mapTo[Option[ConnectionContext]]
            } {
              connContextOpt match {
                case Some(connContext) =>
                  connContext.transport.asInstanceOf[transport.XhrPolling].onGet(ctx.serverConnection)

                case None =>
                  val connecting = Namespace.Connecting(sessionId, query, origins, new transport.XhrPolling(ctx.system))
                  for {
                    connContextOpt <- ctx.namespaces.ask(connecting)(5.seconds).mapTo[Option[ConnectionContext]]
                    connContext <- connContextOpt
                  } {
                    connContext.transport.asInstanceOf[transport.XhrPolling].sendPacket(ConnectPacket())
                    connContext.transport.asInstanceOf[transport.XhrPolling].onGet(ctx.serverConnection)
                    connContext.connectionActive ! Awake
                  }
              }
            }
            Some(true)
          case _ => None
        }
      case _ => None
    }
  }

  /**
   * Test http post request under socketio
   */
  object HttpPost {
    def unapply(req: HttpRequest)(implicit ctx: SoConnectingContext): Option[Boolean] = req match {
      case HttpRequest(HttpMethods.POST, uri, _, entity, HttpProtocols.`HTTP/1.1`) =>
        val origins = req.headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
        uri.path.toString.split("/") match {
          case Array("", SOCKET_IO, protocalVersion, transport.XhrPolling.ID, sessionId) =>
            import ctx.ec
            for {
              connContextOpt <- ctx.namespaces.ask(Namespace.AskConnectionContext(sessionId))(5.seconds).mapTo[Option[ConnectionContext]]
              connContext <- connContextOpt
            } {
              connContext.transport.asInstanceOf[transport.XhrPolling].onPost(ctx.serverConnection, entity.data.toByteString)
            }
            Some(true)
          case _ => None
        }
      case _ => None
    }
  }
}
