/**
 * Copyright (C) 2017 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.easy.download

import java.io.FileNotFoundException
import java.nio.file.{ Path, Paths }
import java.util.UUID

import nl.knaw.dans.easy.download.components.{ FileItem, License, User }
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import nl.knaw.dans.lib.logging.servlet._
import org.eclipse.jetty.http.HttpStatus._
import org.scalatra._
import org.scalatra.auth.strategy.BasicAuthStrategy.BasicAuthRequest
import scalaj.http.HttpResponse

import scala.util.{ Failure, Success, Try }

class EasyDownloadServlet(app: EasyDownloadApp) extends ScalatraServlet
  with ServletLogger
  with PlainLogFormatter
  with LogResponseBodyOnError
  with DebugEnhancedLogging {
  logger.info("File Download Servlet running...")

  private val naan = app.configuration.properties.getString("ark.name-assigning-authority-number")

  get("/") {
    contentType = "text/plain"
    Ok(s"EASY Download Service running v${ app.configuration.version }.")
  }

  get(s"/ark:/$naan/:uuid/*") {
    val authRequest = new BasicAuthRequest(request)
    val userName = { Option(authRequest.username).getOrElse("ANONYMOUS") }
    logger.info(s"file download requested by $userName for $params")

    (getUUID, getPath) match {
      case (Success(uuid), Success(Some(path))) => download(authRequest, userName, uuid, path)
      case (Success(_), Success(None)) => BadRequest("file path is empty")
      case (Failure(t), _) => BadRequest(t.getMessage) // invalid uuid
      case (_, Failure(t)) => BadRequest(t.getMessage) // invalid path
    }
  }

  private def getUUID = Try {
    UUID.fromString(params("uuid"))
  }

  private def getPath = Try {
    multiParams("splat").find(!_.trim.isEmpty).map(Paths.get(_))
  }

  private def download(authRequest: BasicAuthRequest, userName: String, uuid: UUID, path: Path) = {
    val fileItem = app.authorisation.getFileItem(uuid, path)
    getUser(authRequest, userName, fileItem.toOption) match {
      case Success(user) => respond(uuid, path, app.downloadFile(uuid, path, fileItem, user, () => response.outputStream))
      case Failure(InvalidUserPasswordException(_, _)) => Unauthorized()
      case Failure(AuthenticationNotAvailableException(_)) => ServiceUnavailable("Authentication service not available, try anonymous download")
      case Failure(AuthenticationTypeNotSupportedException(_)) => BadRequest("Only anonymous download or basic authentication supported")
      case Failure(t) =>
        logger.error(s"not expected exception", t)
        InternalServerError("not expected exception")
    }
  }

  private def getUser(authRequest: BasicAuthRequest, userName: String, fileItem: Option[FileItem]): Try[Option[User]] = {
    if (fileItem.nonEmpty && fileItem.get.isOpenAccess)
      Success(Some(User(userName, Seq.empty)))
    else
      app.authenticate(authRequest)
  }

  private def respond(uuid: UUID, path: Path, copyResult: Try[Unit]) = {
    copyResult match {
      case Success(()) => sendOkResponse(uuid, path)
      case Failure(HttpStatusException(message, HttpResponse(_, SERVICE_UNAVAILABLE_503, _))) => ServiceUnavailable(message)
      case Failure(HttpStatusException(message, HttpResponse(_, REQUEST_TIMEOUT_408, _))) => RequestTimeout(message)
      case Failure(HttpStatusException(_, HttpResponse(_, NOT_FOUND_404, _))) => NotFound(s"not found: $uuid/$path")
      case Failure(NotAccessibleException(message)) => Forbidden(message)
      case Failure(_: FileNotFoundException) => NotFound(s"not found: $uuid/$path") // in fact: not visible
      case Failure(t) =>
        logger.error(t.getMessage, t)
        InternalServerError("not expected exception")
    }
  }

  private def sendOkResponse(uuid: UUID, path: Path) = {
    val licenseLinkText = getLicenseLinkText(uuid, path).getOrElse(None)
    licenseLinkText.foreach(response.addHeader("Link", _))
    Ok()
  }

  private def getLicenseLinkText(uuid: UUID, path: Path): Try[Option[String]] = {
    for {
      fileItem <- app.getFileItem(uuid, path)
      licenseLinkText <- new License(fileItem, app.configuration.licenses).getLicenseLinkText
    } yield licenseLinkText
  }
}
