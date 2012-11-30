package lila
package setup

import http.Context
import game.{ DbGame, GameRepo, PgnRepo, Pov }
import user.User
import chess.{ Game, Board, Color ⇒ ChessColor }
import ai.Ai
import lobby.{ Hook, Fisherman }
import i18n.I18nDomain
import controllers.routes

import play.api.libs.json.{ Json, JsObject }
import scalaz.effects._

final class Processor(
    configRepo: UserConfigRepo,
    friendConfigMemo: FriendConfigMemo,
    gameRepo: GameRepo,
    pgnRepo: PgnRepo,
    fisherman: Fisherman,
    timelinePush: DbGame ⇒ IO[Unit],
    ai: () ⇒ Ai) extends core.Futuristic {

  def ai(config: AiConfig)(implicit ctx: Context): IO[Pov] = for {
    _ ← ~(ctx.me map (user ⇒ configRepo.update(user)(_ withAi config)))
    pov = config.pov
    game = ctx.me.fold(pov.game)(user ⇒ pov.game.updatePlayer(pov.color, _ withUser user))
    _ ← gameRepo insert game
    _ ← gameRepo denormalizeStarted game
    _ ← timelinePush(game)
    pov2 ← game.player.isHuman.fold(
      io(pov),
      for {
        initialFen ← game.variant.standard.fold(
          io(none[String]),
          gameRepo initialFen game.id)
        pgnString ← pgnRepo get game.id
        aiResult ← { ai().play(game, pgnString, initialFen) map (_.err) }.toIo
        (newChessGame, move) = aiResult
        (progress, pgn) = game.update(newChessGame, move)
        _ ← gameRepo save progress
        _ ← pgnRepo.save(game.id, pgn)
      } yield pov withGame progress.game
    )
  } yield pov2

  def friend(config: FriendConfig)(implicit ctx: Context): IO[Pov] = for {
    _ ← ~(ctx.me map (user ⇒ configRepo.update(user)(_ withFriend config)))
    pov = config.pov
    game = ctx.me.fold(pov.game)(user ⇒ pov.game.updatePlayer(pov.color, _ withUser user))
    _ ← gameRepo insert game
    _ ← timelinePush(game)
    _ ← friendConfigMemo.set(pov.game.id, config)
  } yield pov

  def hook(config: HookConfig)(implicit ctx: Context): IO[Hook] = for {
    _ ← ~(ctx.me map { user ⇒ configRepo.update(user)(_ withHook config) })
    hook = config hook ctx.me
    _ ← fisherman add hook
  } yield hook

  def api(implicit ctx: Context): IO[JsObject] = {
    val domain = "http://" + I18nDomain(ctx.req.domain).commonDomain
    val config = ApiConfig
    val pov = config.pov
    val game = ctx.me.fold(pov.game)(user ⇒ pov.game.updatePlayer(pov.color, _ withUser user)).start
    import chess.Color._
    for {
      _ ← gameRepo insert game
      _ ← timelinePush(game)
    } yield Json.obj(
      White.name -> (domain + routes.Round.player(game fullIdOf White).url),
      Black.name -> (domain + routes.Round.player(game fullIdOf Black).url)
    )
  }
}
