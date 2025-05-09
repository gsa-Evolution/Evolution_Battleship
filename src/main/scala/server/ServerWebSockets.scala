package server

import cats.effect.implicits._
import cats.syntax.all._
import cats.effect.std.Queue
import cats.effect.{IO, Ref, Resource, ResourceApp}
import com.comcast.ip4s.IpLiteralSyntax
import io.circe.syntax.EncoderOps
import io.circe._
import io.circe.generic.auto.exportEncoder
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import server.ServerGameState._
import org.http4s.circe._

import java.util.UUID
import scala.concurrent.duration.DurationInt

object ServerWebSockets extends ResourceApp.Forever {
  type PlayerId    = String
  type BoardCanvas = Map[(Int, Int), Int]

  // Represents a game room with the game state and WebSocket connections of players
  final case class GameRoom(
      state: ServerGameState,
      webSockets: Map[PlayerId, Queue[IO, WebSocketFrame.Text]]
  ) {
    // Handles received commands and updates the game state
    def handleCommand(c: ServerCommand): (GameRoom, Either[ServerGameState.BattleshipError, ServerGameState]) = {
      val newStateOrError =
        for {
          updatedState <- c match {
                            case ServerCommand.Join(playerId)                    => state.join(playerId)
                            case ServerCommand.PlaceShips(playerId, placements)  => state.placeShips(playerId, placements)
                            case ServerCommand.AttackShips(playerId, coordinate) => state.attackShips(playerId, coordinate)
                          }
        } yield copy(state = updatedState)

      (newStateOrError.getOrElse(this), newStateOrError.map(_.state))
    }
  }

  private val corsService = CORS.policy.withAllowOriginHost(_.host.value.matches("localhost")).withAllowCredentials(true)

  // Main method that starts the server
  override def run(args: List[String]): Resource[IO, Unit] = {
    for {
      // Initializes a map of game rooms
      gameRooms <- Ref.of[IO, Map[UUID, Ref[IO, GameRoom]]](Map.empty).toResource
      // Configures and starts the WebSocket server
      _         <- EmberServerBuilder
                     .default[IO]
                     .withHost(host"localhost")
                     .withPort(port"8000")
                     .withHttpWebSocketApp(wsb => corsService(QueueRoutes(gameRooms, wsb).orNotFound))
                     .build
    } yield ()
  }

  // Defines HTTP routes for creating and joining games
  private object QueueRoutes {
    def apply(
        gameRooms: Ref[IO, Map[UUID, Ref[IO, GameRoom]]],
        webSocketBuilder: WebSocketBuilder2[IO]
    ): HttpRoutes[IO] =
      HttpRoutes.of[IO] {
        // Route to create a new game
        case POST -> Root / "createGame" =>
          for {
            id       <- IO.randomUUID
            roomRef  <- Ref.of[IO, GameRoom](
                          GameRoom(
                            state = AwaitingPlayersServerPhase(Set.empty),
                            webSockets = Map.empty
                          )
                        )
            _        <- gameRooms.update(_.updated(id, roomRef))
            response <- Created(id.toString)
          } yield response

        // Route to join an existing game
        case GET -> Root / "join" / roomId / playerId =>
          // Sends the game state to all players
          def sendStateToAllPlayers(
              newState: ServerGameState,
              stateRef: Ref[IO, GameRoom]
          ) = {
            val messagesToPlayers = newState.playerIds.toList.map { playerId =>
              playerId -> GameStateResponse.forPlayer(playerId, newState)
            }

            for {
              gameRoom <- stateRef.get
              _        <- messagesToPlayers.traverse_ { case (playerId, message) =>
                            gameRoom.webSockets.get(playerId).traverse_ { playerWebSocket =>
                              val wsFrame = WebSocketFrame.Text(message.asJson.noSpaces)
                              playerWebSocket.offer(wsFrame)
                            }
                          }
            } yield ()
          }

          for {
            id            <- IO(UUID.fromString(roomId))
            maybeStateRef <- gameRooms.get.map(_.get(id))
            response      <- maybeStateRef match {
                               case Some(stateRef) =>
                                 for {
                                   queue    <- Queue.unbounded[IO, WebSocketFrame.Text]
                                   result   <- stateRef.modify { _.handleCommand(ServerCommand.Join(playerId)) }
                                   _        <- if (result.isRight) stateRef.update { room =>
                                                 room.copy(webSockets = room.webSockets.updated(playerId, queue))
                                               }
                                               else IO.unit
                                   response <- result match {
                                                 case Left(error)     => BadRequest(s"Failed to join room $id: $error")
                                                 case Right(newState) =>
                                                   for {
                                                     response <- webSocketBuilder.build(
                                                                   send = fs2.Stream
                                                                     .awakeEvery[IO](10.seconds)
                                                                     .map(_ => WebSocketFrame.Ping())
                                                                     .merge(fs2.Stream.fromQueueUnterminated(queue)),
                                                                   receive = _.foreach {
                                                                     case text: WebSocketFrame.Text =>
                                                                       jawn.decode[ServerRequest](text.str) match {
                                                                         case Left(decodingError) =>
                                                                           queue.offer(
                                                                             WebSocketFrame.Text(decodingError.toString)
                                                                           )
                                                                         case Right(request)      =>
                                                                           // Match the request type and create the corresponding command
                                                                           val command = request match {
                                                                             case ServerRequest.PlaceShips(
                                                                                   placements
                                                                                 ) =>
                                                                               ServerCommand.PlaceShips(
                                                                                 playerId,
                                                                                 placements
                                                                               )
                                                                             case ServerRequest.AttackShips(coordinate) =>
                                                                              ServerCommand.AttackShips(
                                                                                playerId,
                                                                                coordinate
                                                                              )
                                                                           }

                                                                           for {
                                                                             // Handle the command and update the game state
                                                                             newStateOrError <- stateRef.modify {
                                                                                                  _.handleCommand(
                                                                                                    command
                                                                                                  )
                                                                                                }
                                                                             // Send the updated state to all players
                                                                             _               <- newStateOrError match {
                                                                                                  case Left(e)         =>
                                                                                                    queue.offer(
                                                                                                      WebSocketFrame.Text(e.toString)
                                                                                                    )
                                                                                                  case Right(newState) =>
                                                                                                    sendStateToAllPlayers(
                                                                                                      newState,
                                                                                                      stateRef
                                                                                                    )
                                                                                                }
                                                                           } yield ()
                                                                       }

                                                                     case _ => IO.unit
                                                                   }
                                                                 )
                                                     // Send the updated state to all players after joining
                                                     _        <- sendStateToAllPlayers(newState, stateRef)
                                                   } yield response
                                               }
                                 } yield response

                               case None => NotFound(s"Room $id is not found")
                             }
          } yield response

        case GET -> Root / "rooms" =>
          for {
            roomsList <- gameRooms.get.map(_.toList)
            gameRoomInfos <- roomsList.traverse { case (id, roomRef) =>
              roomRef.get.map { room =>
                val hasEnded = room.state match {
                  case _: ServerGameState.WinServerPhase => true
                  case _                                 => false
                }
                GameRoomResponse(id, room.webSockets.size, room.state.playerIds.toList, hasEnded)
              }
            }
            response <- Ok(gameRoomInfos.asJson)
          } yield response
      }
  }
}
