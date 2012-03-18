package controllers

import lila.http._
import DataForm._

import play.api._
import mvc._

object Application extends LilaController {

  def sync(gameId: String, color: String, version: Int, fullId: String) = Action {
    JsonOk(env.syncer.sync(gameId, color, version, Some(fullId)).unsafePerformIO)
  }

  def syncPublic(gameId: String, color: String, version: Int) = Action {
    JsonOk(env.syncer.sync(gameId, color, version, None).unsafePerformIO)
  }

  def move(fullId: String) = Action { implicit request ⇒
    ValidOk(moveForm.bindFromRequest.toValid flatMap { move ⇒
      env.server.play(fullId, move._1, move._2, move._3).unsafePerformIO
    })
  }

  def ping() = Action { implicit request =>
    JsonOk(env.pinger.ping(
      get("username"),
      get("player_key"),
      get("watcher"),
      get("get_nb_watchers")
    ).unsafePerformIO)
  }
}